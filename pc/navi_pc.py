"""navi-pc: deixa a Navi (no celular) controlar este PC Windows pela rede de casa.

Uso:
    NaviPC.exe              inicia (ícone perto do relógio) e mostra o código de pareamento
    NaviPC.exe --instalar   inicia junto com o Windows e libera no firewall
    NaviPC.exe --desinstalar
    python navi_pc.py       o mesmo, rodando direto pelo Python
"""

import ctypes
import hmac
import json
import logging
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

VERSION = "2.0"
APP_NAME = "NaviPC"
IS_WIN = sys.platform == "win32"
BASE = Path(os.environ.get("APPDATA", Path.home())) / APP_NAME
BASE.mkdir(parents=True, exist_ok=True)
CONFIG = BASE / "config.json"
logging.basicConfig(filename=BASE / "navi-pc.log", level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("navi-pc")


def load_config():
    cfg = {}
    try:
        cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    except Exception:
        pass
    changed = False
    if not cfg.get("token"):
        cfg["token"] = f"{secrets.randbelow(10**6):06d}"
        changed = True
    cfg.setdefault("port", 8765)
    cfg.setdefault("allow_powershell", True)
    if changed or not CONFIG.exists():
        CONFIG.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


CFG = load_config()
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


def pairing_text():
    return (f"Endereço: {local_ip()}:{CFG['port']}\n"
            f"Código de pareamento: {CFG['token']}\n\n"
            "No celular: Navi > Ajustes > PC > Procurar PC, digite o código e toque em Parear.")


# ------------------------------------------------------------------ servidor

_falhas = {}  # ip -> [horários de tentativas erradas]


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

    def _authorized(self):
        ip = self.client_address[0]
        agora = time.time()
        recentes = [t for t in _falhas.get(ip, []) if agora - t < 60]
        _falhas[ip] = recentes
        if len(recentes) >= 5:
            self._send(429, {"status": "erro", "erro": "Muitas tentativas. Espere um minuto."})
            return False
        token = self.headers.get("X-Navi-Token", "")
        if hmac.compare_digest(token.encode(), str(CFG["token"]).encode()):
            return True
        recentes.append(agora)
        self._send(401, {"status": "erro", "erro": "Código de pareamento errado."})
        return False

    def do_GET(self):
        if self.path == "/ping":
            self._send(200, {"app": "navi-pc", "name": socket.gethostname(), "version": VERSION})
        elif self.path == "/tools":
            if self._authorized():
                self._send(200, {"tools": acoes.declarations(), "mac": mac_address(), "name": socket.gethostname()})
        else:
            self._send(404, {"status": "erro", "erro": "não encontrado"})

    def do_POST(self):
        if self.path != "/run":
            self._send(404, {"status": "erro", "erro": "não encontrado"})
            return
        if not self._authorized():
            return
        try:
            length = min(int(self.headers.get("Content-Length", "0")), 1_000_000)
            req = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            self._send(400, {"status": "erro", "erro": "pedido inválido"})
            return
        name, args = req.get("name", ""), req.get("args") or {}
        log.info("ação %s %s", name, json.dumps(args, ensure_ascii=False)[:300])
        result = acoes.run(name, args)
        self._send(200, result)


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
    # Libera a porta no firewall (pede permissão de administrador uma vez).
    args = (f'advfirewall firewall add rule name="Navi PC" dir=in action=allow protocol=TCP '
            f'localport={CFG["port"]} profile=private')
    ctypes.windll.shell32.ShellExecuteW(None, "runas", "netsh", args, None, 0)
    message("A Navi vai iniciar junto com o Windows.\n\n" + pairing_text())


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


def single_instance():
    """Evita abrir duas cópias (a segunda só mostra o código)."""
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

    def show(icon, item):
        threading.Thread(target=message, args=(pairing_text(),), daemon=True).start()

    def open_folder(icon, item):
        os.startfile(BASE)

    def quit_app(icon, item):
        icon.stop()
        os._exit(0)

    menu = pystray.Menu(
        pystray.MenuItem("Mostrar código de pareamento", show, default=True),
        pystray.MenuItem("Iniciar com o Windows", lambda i, it: install()),
        pystray.MenuItem("Abrir pasta de ajustes", open_folder),
        pystray.MenuItem("Sair", quit_app),
    )
    pystray.Icon(APP_NAME, img, "Navi PC", menu).run()


def main():
    if "--instalar" in sys.argv:
        install()
    elif "--desinstalar" in sys.argv:
        uninstall()
        return
    if not single_instance():
        message("A Navi PC já está aberta.\n\n" + pairing_text())
        return
    threading.Thread(target=serve, daemon=True).start()
    if not CFG.get("shown_code"):
        CFG["shown_code"] = True
        CONFIG.write_text(json.dumps(CFG, indent=2), encoding="utf-8")
        threading.Thread(target=message, args=("Navi PC está rodando!\n\n" + pairing_text(),), daemon=True).start()
    if IS_WIN:
        tray()
    else:
        print(pairing_text())
        while True:
            time.sleep(3600)


if __name__ == "__main__":
    main()
