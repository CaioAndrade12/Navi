"""navi-pc: deixa a Navi (no celular) controlar este PC Windows pela rede de casa.

Uso:
    NaviPC.exe              inicia (ícone ✦ perto do relógio)
    NaviPC.exe --instalar   inicia junto com o Windows e libera no firewall
    NaviPC.exe --desinstalar
    python navi_pc.py       o mesmo, rodando direto pelo Python

Segurança:
    - Parear exige um código de 8 dígitos que só aparece na tela do PC, por poucos minutos.
    - Cada celular pareado recebe uma chave aleatória de 256 bits, que nunca mais trafega.
    - Todo pedido é assinado (HMAC-SHA256) com horário e número único: quem copiar o
      tráfego do Wi-Fi não consegue repetir nem forjar comandos.
"""

import ctypes
import hashlib
import hmac
import json
import logging
import logging.handlers
import os
import secrets
import socket
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import acoes

VERSION = "2.1"
APP_NAME = "NaviPC"
IS_WIN = sys.platform == "win32"
BASE = Path(os.environ.get("APPDATA", Path.home())) / APP_NAME
BASE.mkdir(parents=True, exist_ok=True)
CONFIG = BASE / "config.json"

_handler = logging.handlers.RotatingFileHandler(BASE / "navi-pc.log", maxBytes=1_000_000, backupCount=1,
                                                encoding="utf-8")
_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
log = logging.getLogger("navi-pc")
log.setLevel(logging.INFO)
log.addHandler(_handler)

_lock = threading.Lock()


def load_config():
    cfg = {}
    try:
        cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    except Exception:
        pass
    cfg.pop("token", None)  # versão antiga usava um código fixo de 6 dígitos
    cfg.setdefault("port", 8765)
    cfg.setdefault("allow_powershell", True)
    cfg.setdefault("devices", [])
    return cfg


CFG = load_config()


def save_config():
    with _lock:
        CONFIG.write_text(json.dumps(CFG, indent=2, ensure_ascii=False), encoding="utf-8")


save_config()
acoes.SETTINGS["allow_powershell"] = bool(CFG.get("allow_powershell", True))


def local_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))  # não envia nada, só escolhe a placa de rede certa
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def mac_address():
    m = uuid.getnode()
    return ":".join(f"{(m >> s) & 0xFF:02X}" for s in range(40, -1, -8))


def device_id(key_hex):
    return hashlib.sha256(bytes.fromhex(key_hex)).hexdigest()[:16]


# ------------------------------------------------------------------ pareamento

PAIRING = {"code": None, "until": 0.0, "attempts": 0}


def open_pairing(minutes=5):
    with _lock:
        PAIRING["code"] = f"{secrets.randbelow(10**8):08d}"
        PAIRING["until"] = time.time() + minutes * 60
        PAIRING["attempts"] = 0
    log.info("pareamento aberto por %s min", minutes)
    return PAIRING["code"]


def pairing_open():
    return PAIRING["code"] is not None and time.time() < PAIRING["until"]


def close_pairing():
    with _lock:
        PAIRING["code"] = None
        PAIRING["until"] = 0.0


def pairing_text():
    code = PAIRING["code"] if pairing_open() else None
    n = len(CFG["devices"])
    txt = f"Endereço deste PC: {local_ip()}:{CFG['port']}\nCelulares pareados: {n}\n\n"
    if code:
        mins = max(1, int((PAIRING["until"] - time.time()) // 60))
        txt += (f"Código de pareamento: {code[:4]} {code[4:]}\n(vale por {mins} min)\n\n"
                "No celular: Navi > Ajustes > PC > Procurar PC, digite o código e toque em Parear.")
    else:
        txt += "Para parear um celular, clique no ícone ✦ e escolha 'Parear um celular'."
    return txt


# ------------------------------------------------------------------ servidor

_falhas = {}   # ip -> [horários de tentativas erradas]
_nonces = {}   # nonce -> horário (anti-repetição)
MAX_SKEW = 300  # segundos de diferença de relógio aceitos entre celular e PC


def _too_many_failures(ip):
    with _lock:
        agora = time.time()
        recentes = [t for t in _falhas.get(ip, []) if agora - t < 60]
        _falhas[ip] = recentes
        return len(recentes) >= 8


def _fail(ip):
    with _lock:
        _falhas.setdefault(ip, []).append(time.time())


def _nonce_ok(nonce, ts):
    with _lock:
        agora = time.time()
        for n, t in list(_nonces.items()):
            if agora - t > 2 * MAX_SKEW:
                del _nonces[n]
        if nonce in _nonces:
            return False
        _nonces[nonce] = ts
        return True


def sign(key_hex, method, path, ts, nonce, body):
    msg = f"{method}\n{path}\n{ts}\n{nonce}\n{hashlib.sha256(body).hexdigest()}".encode()
    return hmac.new(bytes.fromhex(key_hex), msg, hashlib.sha256).hexdigest()


class Handler(BaseHTTPRequestHandler):
    server_version = "navi-pc/" + VERSION

    def log_message(self, fmt, *args):
        log.info("%s %s", self.client_address[0], fmt % args)

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = min(int(self.headers.get("Content-Length", "0") or 0), 1_000_000)
        return self.rfile.read(length) if length > 0 else b""

    def _verify(self, body):
        """Confere a assinatura do pedido. Devolve True ou já responde com o erro."""
        ip = self.client_address[0]
        if _too_many_failures(ip):
            self._send(429, {"status": "erro", "erro": "Muitas tentativas. Espere um minuto."})
            return False
        dev = self.headers.get("X-Navi-Device", "")
        ts = self.headers.get("X-Navi-Ts", "")
        nonce = self.headers.get("X-Navi-Nonce", "")
        sig = self.headers.get("X-Navi-Sig", "")
        device = next((d for d in CFG["devices"] if device_id(d["key"]) == dev), None)
        if device is None:
            _fail(ip)
            self._send(401, {"status": "erro", "erro": "Celular não pareado com este PC. Pareie de novo."})
            return False
        try:
            ts_i = int(ts)
        except ValueError:
            ts_i = 0
        if abs(time.time() - ts_i) > MAX_SKEW:
            self._send(401, {"status": "erro", "erro": "Relógio do celular e do PC muito diferentes."})
            return False
        expected = sign(device["key"], self.command, self.path, ts, nonce, body)
        if not hmac.compare_digest(expected, sig):
            _fail(ip)
            self._send(401, {"status": "erro", "erro": "Assinatura inválida."})
            return False
        if len(nonce) < 16 or not _nonce_ok(nonce, ts_i):
            self._send(401, {"status": "erro", "erro": "Pedido repetido."})
            return False
        return True

    def do_GET(self):
        if self.path == "/ping":
            self._send(200, {"app": "navi-pc", "name": socket.gethostname(), "version": VERSION,
                             "pairing": pairing_open()})
        elif self.path == "/tools":
            if self._verify(b""):
                self._send(200, {"tools": acoes.declarations(), "mac": mac_address(),
                                 "name": socket.gethostname()})
        else:
            self._send(404, {"status": "erro", "erro": "não encontrado"})

    def do_POST(self):
        try:
            body = self._body()
        except Exception:
            self._send(400, {"status": "erro", "erro": "pedido inválido"})
            return
        if self.path == "/pair":
            self._pair(body)
        elif self.path == "/run":
            if not self._verify(body):
                return
            try:
                req = json.loads(body or b"{}")
            except Exception:
                self._send(400, {"status": "erro", "erro": "pedido inválido"})
                return
            name, args = req.get("name", ""), req.get("args") or {}
            # No log vai só o nome da ação e os nomes dos parâmetros (não o conteúdo).
            log.info("ação %s %s", name, sorted(args.keys()) if isinstance(args, dict) else "")
            self._send(200, acoes.run(name, args))
        else:
            self._send(404, {"status": "erro", "erro": "não encontrado"})

    def _pair(self, body):
        ip = self.client_address[0]
        if not pairing_open():
            self._send(403, {"status": "erro",
                             "erro": "O pareamento está fechado. No PC, clique no ícone ✦ e escolha 'Parear um celular'."})
            return
        try:
            req = json.loads(body or b"{}")
        except Exception:
            req = {}
        code = "".join(ch for ch in str(req.get("code", "")) if ch.isdigit())
        with _lock:
            PAIRING["attempts"] += 1
            tentativas = PAIRING["attempts"]
        if tentativas > 5:
            close_pairing()
            self._send(429, {"status": "erro", "erro": "Muitas tentativas. Abra o pareamento de novo no PC."})
            return
        if not hmac.compare_digest(code.encode(), str(PAIRING["code"]).encode()):
            _fail(ip)
            self._send(401, {"status": "erro", "erro": "Código errado."})
            return
        key = secrets.token_hex(32)
        name = str(req.get("device", "celular"))[:60]
        CFG["devices"].append({"name": name, "key": key, "added": time.strftime("%Y-%m-%d %H:%M")})
        save_config()
        close_pairing()
        log.info("celular pareado: %s (%s)", name, ip)
        threading.Thread(target=message, args=(f"Celular pareado: {name}",), daemon=True).start()
        self._send(200, {"status": "ok", "key": key, "name": socket.gethostname(), "mac": mac_address()})


def serve():
    srv = ThreadingHTTPServer(("0.0.0.0", int(CFG["port"])), Handler)
    srv.daemon_threads = True
    log.info("ouvindo em %s:%s", local_ip(), CFG["port"])
    srv.serve_forever()


# ------------------------------------------------------------------ Windows: avisos, início automático, firewall

def message(text, title="Navi PC"):
    if IS_WIN:
        ctypes.windll.user32.MessageBoxW(0, text, title, 0x40 | 0x1000)
    else:
        print(f"{title}\n{text}")


def show_pairing(minutes=5):
    open_pairing(minutes)
    threading.Thread(target=message, args=(pairing_text(),), daemon=True).start()


def exe_command():
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    pyw = Path(sys.executable).with_name("pythonw.exe")
    py = pyw if pyw.exists() else Path(sys.executable)
    return f'"{py}" "{Path(__file__).resolve()}"'


def install():
    import winreg
    k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                       winreg.KEY_SET_VALUE)
    winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, exe_command())
    winreg.CloseKey(k)
    # Libera a porta só na rede privada (pede permissão de administrador uma vez).
    args = (f'advfirewall firewall add rule name="Navi PC" dir=in action=allow protocol=TCP '
            f'localport={CFG["port"]} profile=private')
    ctypes.windll.shell32.ShellExecuteW(None, "runas", "netsh", args, None, 0)
    message("A Navi vai iniciar junto com o Windows.")


def uninstall():
    import winreg
    try:
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0,
                           winreg.KEY_SET_VALUE)
        winreg.DeleteValue(k, APP_NAME)
        winreg.CloseKey(k)
    except OSError:
        pass
    ctypes.windll.shell32.ShellExecuteW(None, "runas", "netsh",
                                        'advfirewall firewall delete rule name="Navi PC"', None, 0)
    message("A Navi não vai mais iniciar com o Windows.")


def unpair_all():
    CFG["devices"] = []
    save_config()
    message("Todos os celulares foram despareados. Pareie de novo quando quiser.")


def toggle_powershell():
    CFG["allow_powershell"] = not CFG.get("allow_powershell", True)
    acoes.SETTINGS["allow_powershell"] = CFG["allow_powershell"]
    save_config()
    message("Comandos de PowerShell: " + ("PERMITIDOS (sempre com confirmação no celular)."
                                          if CFG["allow_powershell"] else "BLOQUEADOS."))


def single_instance():
    """Evita abrir duas cópias."""
    if not IS_WIN:
        return True
    ctypes.windll.kernel32.CreateMutexW(None, False, "Global\\NaviPC")
    return ctypes.windll.kernel32.GetLastError() != 183  # ERROR_ALREADY_EXISTS


def tray():
    """Ícone perto do relógio. Sem pystray, fica só rodando em segundo plano."""
    try:
        import pystray
        from PIL import Image, ImageDraw
    except ImportError:
        while True:
            time.sleep(3600)
    img = Image.new("RGBA", (64, 64), (14, 26, 58, 255))
    d = ImageDraw.Draw(img)
    d.polygon([(32, 6), (38, 26), (58, 32), (38, 38), (32, 58), (26, 38), (6, 32), (26, 26)], fill=(255, 255, 255, 255))

    def quit_app(icon, item):
        icon.stop()
        os._exit(0)

    menu = pystray.Menu(
        pystray.MenuItem("Parear um celular", lambda i, it: show_pairing(), default=True),
        pystray.MenuItem("Status", lambda i, it: threading.Thread(target=message, args=(pairing_text(),),
                                                                  daemon=True).start()),
        pystray.MenuItem("Permitir/bloquear PowerShell", lambda i, it: toggle_powershell()),
        pystray.MenuItem("Iniciar com o Windows", lambda i, it: install()),
        pystray.MenuItem("Desparear todos os celulares", lambda i, it: unpair_all()),
        pystray.MenuItem("Abrir pasta de ajustes", lambda i, it: os.startfile(BASE)),
        pystray.MenuItem("Sair", quit_app),
    )
    pystray.Icon(APP_NAME, img, "Navi PC", menu).run()


def main():
    if "--desinstalar" in sys.argv:
        uninstall()
        return
    if not single_instance():
        message("A Navi PC já está aberta. Use o ícone ✦ perto do relógio.")
        return
    if "--instalar" in sys.argv:
        install()
    threading.Thread(target=serve, daemon=True).start()
    if not CFG["devices"]:
        # Nenhum celular pareado ainda: abre o pareamento por 10 minutos e mostra o código.
        show_pairing(10)
    if IS_WIN:
        tray()
    else:
        print(pairing_text())
        while True:
            time.sleep(3600)


if __name__ == "__main__":
    main()
