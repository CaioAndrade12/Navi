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

import base64
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
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import acoes

VERSION = "2.3"
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


TESTE = {"ativo": False}


def save_config():
    if TESTE["ativo"]:
        return
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
            inicio = time.time()
            res = acoes.run(name, args)
            detalhe = str(res.get("erro") or res.get("resultado") or "")[:160]
            log.info("resultado %s: %s em %.1fs — %s", name, res.get("status"), time.time() - inicio, detalhe)
            self._send(200, res)
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
        if not TESTE["ativo"]:
            threading.Thread(target=message, args=(f"Celular pareado: {name}",), daemon=True).start()
        self._send(200, {"status": "ok", "key": key, "name": socket.gethostname(), "mac": mac_address()})


def serve():
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", int(CFG["port"])), Handler)
    except OSError as e:
        log.error("não consegui abrir a porta %s: %s", CFG["port"], e)
        message(f"A porta {CFG['port']} já está em uso. Feche a outra cópia da Navi PC ou mude a porta no config.json.")
        return
    srv.daemon_threads = True
    log.info("ouvindo em %s:%s (versão %s)", local_ip(), CFG["port"], VERSION)
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


# ------------------------------------------------------------------ diagnóstico de rede e firewall

def caminho_exe():
    return sys.executable if getattr(sys, "frozen", False) else ""


def diagnostico_rede():
    """Procura o que pode impedir o celular de falar com o PC. Devolve lista de problemas (texto)."""
    problemas = []
    ip = local_ip()
    perfis = acoes.ps_json(
        f"Get-NetIPAddress -IPAddress '{ip}' -ErrorAction SilentlyContinue | ForEach-Object {{ "
        "Get-NetConnectionProfile -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue } | "
        "Select-Object Name,InterfaceAlias,@{n='Categoria';e={$_.NetworkCategory.ToString()}}")
    for p in perfis:
        if p.get("Categoria") == "Public":
            problemas.append(f"A rede \"{p.get('Name')}\" está como PÚBLICA, e o Windows bloqueia o celular nela.")
    exe = caminho_exe()
    if exe:
        regras = acoes.ps_json(
            f"Get-NetFirewallApplicationFilter -Program '{exe}' -ErrorAction SilentlyContinue | "
            "Get-NetFirewallRule -ErrorAction SilentlyContinue | Where-Object { $_.Direction -eq 'Inbound' } | "
            "Select-Object DisplayName,@{n='Acao';e={$_.Action.ToString()}},@{n='Ativa';e={$_.Enabled.ToString()}}",
            timeout=60)
        if any(r.get("Acao") == "Block" and r.get("Ativa") == "True" for r in regras):
            problemas.append("Existe uma regra do Firewall BLOQUEANDO a Navi PC (acontece quando se clica em Cancelar "
                             "no aviso do Windows).")
    return problemas


def corrigir_rede():
    """Torna a rede de casa privada e libera a Navi no Firewall (pede administrador)."""
    exe = caminho_exe()
    ip = local_ip()
    porta = int(CFG["port"])
    script = f"""
$ip = '{ip}'
$idx = (Get-NetIPAddress -IPAddress $ip -ErrorAction SilentlyContinue).InterfaceIndex
if ($idx) {{ Get-NetConnectionProfile -InterfaceIndex $idx -ErrorAction SilentlyContinue |
  Where-Object {{ $_.NetworkCategory -eq 'Public' }} | Set-NetConnectionProfile -NetworkCategory Private }}
Remove-NetFirewallRule -DisplayName 'Navi PC' -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName 'Navi PC' -Direction Inbound -Action Allow -Protocol TCP -LocalPort {porta} -Profile Private,Domain | Out-Null
"""
    if exe:
        script += f"""
$exe = '{exe}'
Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue | Get-NetFirewallRule -ErrorAction SilentlyContinue |
  Where-Object {{ $_.Action -eq 'Block' }} | Remove-NetFirewallRule
Remove-NetFirewallRule -DisplayName 'Navi PC (programa)' -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName 'Navi PC (programa)' -Direction Inbound -Action Allow -Program $exe -Profile Private,Domain | Out-Null
"""
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    ctypes.windll.shell32.ShellExecuteW(None, "runas", "powershell.exe",
                                        f"-NoProfile -ExecutionPolicy Bypass -EncodedCommand {encoded}", None, 0)


def checar_rede_no_inicio():
    """Na primeira vez de cada versão, avisa se a rede/firewall estiver atrapalhando."""
    if CFG.get("rede_ok_versao") == VERSION:
        return
    try:
        problemas = diagnostico_rede()
    except Exception as e:
        log.info("diagnóstico de rede falhou: %s", e)
        return
    if not problemas:
        CFG["rede_ok_versao"] = VERSION
        save_config()
        return
    log.info("problemas de rede: %s", problemas)
    texto = ("A Navi encontrou algo que pode impedir o celular de controlar este PC:\n\n- " + "\n- ".join(problemas) +
             "\n\nQuer que eu corrija agora? (o Windows vai pedir permissão de administrador)")
    if IS_WIN and ctypes.windll.user32.MessageBoxW(0, texto, "Navi PC", 0x04 | 0x30 | 0x1000) == 6:  # Sim
        corrigir_rede()


# ------------------------------------------------------------------ autoteste

def autoteste(ci=False):
    """Testa o NaviPC de verdade: servidor, pareamento, assinatura e as ações. Devolve (passou, texto)."""
    linhas, criticos = [], []

    def reg(nome, passou, detalhe="", critico=False):
        linhas.append(("OK   " if passou else "FALHA") + f"  {nome}" + (f" — {detalhe}" if detalhe else ""))
        if critico and not passou:
            criticos.append(nome)

    TESTE["ativo"] = True
    dispositivos = list(CFG["devices"])
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        srv.daemon_threads = True
        porta = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{porta}"

        def http(metodo, caminho, corpo=b"", cab=None):
            req = urllib.request.Request(base + caminho, data=corpo if metodo == "POST" else None,
                                         method=metodo, headers=cab or {})
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    return r.status, json.loads(r.read())
            except urllib.error.HTTPError as e:
                return e.code, json.loads(e.read() or b"{}")

        st, r = http("GET", "/ping")
        reg("servidor responde", st == 200 and r.get("app") == "navi-pc", f"versão {r.get('version')}", True)

        codigo = open_pairing(1)
        st, r = http("POST", "/pair", json.dumps({"code": codigo, "device": "autoteste"}).encode())
        chave = r.get("key", "")
        reg("pareamento", st == 200 and len(chave) == 64, critico=True)

        def assinado(metodo, caminho, corpo=b""):
            ts, nonce = str(int(time.time())), secrets.token_hex(12)
            cab = {"X-Navi-Device": device_id(chave), "X-Navi-Ts": ts, "X-Navi-Nonce": nonce,
                   "X-Navi-Sig": sign(chave, metodo, caminho, ts, nonce, corpo)}
            return cab

        cab = assinado("GET", "/tools")
        st, r = http("GET", "/tools", cab=cab)
        reg("lista de ações (pedido assinado)", st == 200 and len(r.get("tools", [])) > 10,
            f"{len(r.get('tools', []))} ações", True)
        st2, _ = http("GET", "/tools", cab=cab)
        reg("bloqueia pedido repetido", st2 == 401, critico=True)
        st3, _ = http("GET", "/tools")
        reg("bloqueia pedido sem assinatura", st3 == 401, critico=True)

        def acao(nome, args, critico=False, esperado="ok"):
            corpo = json.dumps({"name": nome, "args": args}).encode()
            st, r = http("POST", "/run", corpo, assinado("POST", "/run", corpo))
            passou = st == 200 and r.get("status") == esperado
            detalhe = str(r.get("resultado") or r.get("erro") or r.get("saida") or "")[:120]
            if nome == "pc_status" and passou:
                detalhe = f"CPU {r.get('cpu_porcentagem')}%, memória {r.get('memoria_porcentagem')}%"
            reg(f"ação {nome} {args if args else ''}".strip(), passou, detalhe, critico)
            return r

        acao("pc_status", {}, critico=True)
        vol = acao("pc_volume", {"acao": "consultar"})
        if vol.get("status") == "ok":
            atual = int(vol.get("volume", 30))
            acao("pc_volume", {"acao": "definir", "nivel": atual})
        acao("pc_janelas", {}, critico=True)
        progs = acoes.programas()
        reg("programas do menu Iniciar", len(progs) > 0, f"{len(progs)} encontrados", True)
        reg("acentos preservados", not any("\ufffd" in n for n, _, _ in progs), critico=True)

        # Nomes falados: jogos, apelidos e links.
        nomes = ["Grand Theft Auto V", "Counter-Strike 2", "Red Dead Redemption 2", "Google Chrome", "Steam"]
        casos = {"gta 5": "Grand Theft Auto V", "cs2": "Counter-Strike 2", "rdr2": "Red Dead Redemption 2",
                 "chrome": "Google Chrome", "abre a steam": "Steam"}
        errados = [q for q, esperado in casos.items() if acoes.jogos.melhor(q, nomes)[1] != esperado]
        reg("entende nomes de jogos e apelidos", not errados, ", ".join(errados), True)
        links = {"g1 ponto globo ponto com": "g1.globo.com", "github ponto com barra caio andrade 12":
                 "github.com/caioandrade12", "site da kabum ponto com ponto br": "kabum.com.br"}
        errados = [f"{q} -> {acoes.link_falado(q)}" for q, esperado in links.items() if acoes.link_falado(q) != esperado]
        reg("entende links falados", not errados, "; ".join(errados), True)
        r = acao("pc_jogos", {}, critico=True)
        linhas[-1] += f" ({len(r.get('jogos', []))} jogos)"

        import tela
        reg("estrutura da tela (DEVMODEW)", ctypes.sizeof(tela.DEVMODEW) == 220,
            f"{ctypes.sizeof(tela.DEVMODEW)} bytes", True)

        def sem_travar(nome, args):
            """A\u00e7\u00f5es de hardware: o PC do GitHub n\u00e3o tem som/monitor, ent\u00e3o "erro" \u00e9 aceit\u00e1vel; travar n\u00e3o."""
            corpo = json.dumps({"name": nome, "args": args}).encode()
            st, r = http("POST", "/run", corpo, assinado("POST", "/run", corpo))
            texto = str(r.get("resultado") or r.get("erro") or r.get("monitores") or "")
            grave = st != 200 or "violation" in texto.lower() or "TypeError" in texto or "AttributeError" in texto
            reg(f"a\u00e7\u00e3o {nome} {args} n\u00e3o trava", not grave, f"{r.get('status')}: {texto[:110]}", True)
            return r

        sem_travar("pc_tela", {"acao": "consultar"})
        sem_travar("pc_saida_som", {"acao": "listar"})
        sem_travar("pc_saida_som", {"acao": "listar", "tipo": "microfone"})
        sem_travar("pc_volume_app", {"acao": "listar"})
        sem_travar("pc_microfone", {"acao": "consultar"})
        sem_travar("pc_brilho", {"acao": "consultar"})
        sem_travar("pc_monitor", {"acao": "consultar"})
        if ci:
            try:
                w, h, f = tela.atual()
                reg("muda a resolu\u00e7\u00e3o (para a mesma)", tela.mudar(w, h, f) == (w, h, f), f"{w}x{h} {f} Hz")
            except Exception as e:
                reg("muda a resolu\u00e7\u00e3o (para a mesma)", False, str(e)[:100])
            try:
                url, como = acoes.site_por_nome("cobasi")
                reg("acha site pelo nome", "cobasi" in url, f"{como}: {url[:80]}")
            except Exception as e:
                reg("acha site pelo nome", False, str(e)[:100])
            # Só no GitHub: abre e fecha o Bloco de Notas de verdade.
            r = acao("pc_abrir", {"alvo": "Bloco de Notas"}, critico=True)
            j = acoes.janelas.achar("notepad") or acoes.janelas.achar("bloco de notas")
            reg("janela do Bloco de Notas apareceu", j is not None, j.titulo if j else "", True)
            if j:
                acoes.janelas.fechar(j.hwnd)
            acao("pc_abrir", {"alvo": "programa que nao existe xyz"}, esperado="erro")
            try:
                vid, titulo = acoes.primeiro_video("Charlie Brown Jr Só os Loucos Sabem")
                reg("busca no YouTube", bool(vid), f"{vid} {titulo}")
            except Exception as e:
                reg("busca no YouTube", False, str(e)[:100])
        acao("pc_powershell", {"comando": "Get-Date -Format yyyy"}, critico=True)
        try:
            problemas = diagnostico_rede()
            reg("rede e firewall", not problemas, "; ".join(problemas) or f"IP {local_ip()}")
        except Exception as e:
            reg("rede e firewall", False, str(e)[:120])
        srv.shutdown()
    except Exception as e:
        reg("autoteste", False, f"{type(e).__name__}: {e}", True)
    finally:
        CFG["devices"] = dispositivos
        TESTE["ativo"] = False
        close_pairing()

    passou = not criticos
    texto = (f"Navi PC {VERSION} — autoteste {'APROVADO' if passou else 'COM FALHAS'}\n"
             f"Data: {time.strftime('%d/%m/%Y %H:%M')}\n\n" + "\n".join(linhas))
    try:
        (BASE / "autoteste.txt").write_text(texto, encoding="utf-8")
    except Exception:
        pass
    return passou, texto


def mostrar_diagnostico():
    passou, texto = autoteste()
    problemas = []
    try:
        problemas = diagnostico_rede()
    except Exception:
        pass
    if problemas and IS_WIN:
        pergunta = texto + "\n\nEncontrei problemas de rede/firewall. Corrigir agora? (pede administrador)"
        if ctypes.windll.user32.MessageBoxW(0, pergunta, "Navi PC — diagnóstico", 0x04 | 0x30 | 0x1000) == 6:
            corrigir_rede()
    else:
        message(texto, "Navi PC — diagnóstico")


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
        pystray.MenuItem("Testar tudo (diagnóstico)", lambda i, it: threading.Thread(target=mostrar_diagnostico,
                                                                                    daemon=True).start()),
        pystray.MenuItem("Corrigir rede/firewall", lambda i, it: corrigir_rede()),
        pystray.MenuItem("Permitir/bloquear PowerShell", lambda i, it: toggle_powershell()),
        pystray.MenuItem("Iniciar com o Windows", lambda i, it: install()),
        pystray.MenuItem("Desparear todos os celulares", lambda i, it: unpair_all()),
        pystray.MenuItem("Abrir pasta de ajustes", lambda i, it: os.startfile(BASE)),
        pystray.MenuItem("Sair", quit_app),
    )
    pystray.Icon(APP_NAME, img, "Navi PC", menu).run()


def main():
    if "--teste" in sys.argv:
        passou, texto = autoteste(ci="--ci" in sys.argv)
        if "--silencioso" not in sys.argv:
            message(texto, "Navi PC — autoteste")
        sys.exit(0 if passou else 1)
    if "--desinstalar" in sys.argv:
        uninstall()
        return
    if not single_instance():
        message("A Navi PC já está aberta. Use o ícone ✦ perto do relógio.")
        return
    if "--instalar" in sys.argv:
        install()
    threading.Thread(target=serve, daemon=True).start()
    threading.Thread(target=checar_rede_no_inicio, daemon=True).start()
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
