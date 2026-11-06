"""Janelas do Windows: listar, achar e trazer para a frente (só ctypes, sem dependências).

Por que existe: quando um programa em segundo plano (o NaviPC) abre outro programa ou um
site, o Windows costuma abrir a janela ATRÁS das outras, piscando na barra de tarefas.
Aqui ficam os truques para a janela aparecer na frente, como se o usuário tivesse clicado.
"""

import ctypes
import os
import re
import sys
import time
import unicodedata
from collections import namedtuple

IS_WIN = sys.platform == "win32"

Janela = namedtuple("Janela", "hwnd titulo exe pid")

if IS_WIN:
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    try:
        dwmapi = ctypes.WinDLL("dwmapi")
    except OSError:
        dwmapi = None

    ULONG_PTR = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]

    class _INPUTUNION(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]

    WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    user32.EnumWindows.argtypes = [WNDENUMPROC, wintypes.LPARAM]
    user32.EnumWindows.restype = wintypes.BOOL
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.IsWindowVisible.restype = wintypes.BOOL
    user32.IsWindow.argtypes = [wintypes.HWND]
    user32.IsWindow.restype = wintypes.BOOL
    user32.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]
    user32.GetWindow.restype = wintypes.HWND
    user32.GetWindowLongW.argtypes = [wintypes.HWND, ctypes.c_int]
    user32.GetWindowLongW.restype = wintypes.LONG
    user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    user32.GetWindowTextLengthW.restype = ctypes.c_int
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetWindowTextW.restype = ctypes.c_int
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetWindowThreadProcessId.restype = wintypes.DWORD
    user32.IsIconic.argtypes = [wintypes.HWND]
    user32.IsIconic.restype = wintypes.BOOL
    user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    user32.ShowWindow.restype = wintypes.BOOL
    user32.SetForegroundWindow.argtypes = [wintypes.HWND]
    user32.SetForegroundWindow.restype = wintypes.BOOL
    user32.BringWindowToTop.argtypes = [wintypes.HWND]
    user32.BringWindowToTop.restype = wintypes.BOOL
    user32.GetForegroundWindow.argtypes = []
    user32.GetForegroundWindow.restype = wintypes.HWND
    user32.AllowSetForegroundWindow.argtypes = [wintypes.DWORD]
    user32.AllowSetForegroundWindow.restype = wintypes.BOOL
    user32.AttachThreadInput.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.BOOL]
    user32.AttachThreadInput.restype = wintypes.BOOL
    user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
    user32.SendInput.restype = wintypes.UINT
    user32.keybd_event.argtypes = [wintypes.BYTE, wintypes.BYTE, wintypes.DWORD, ULONG_PTR]
    user32.keybd_event.restype = None
    user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    user32.PostMessageW.restype = wintypes.BOOL
    kernel32.GetCurrentThreadId.argtypes = []
    kernel32.GetCurrentThreadId.restype = wintypes.DWORD
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
                                                    ctypes.POINTER(wintypes.DWORD)]
    kernel32.QueryFullProcessImageNameW.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    if dwmapi is not None:
        dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]
        dwmapi.DwmGetWindowAttribute.restype = ctypes.c_long

GW_OWNER = 4
GWL_EXSTYLE = -20
WS_EX_TOOLWINDOW = 0x00000080
WS_EX_APPWINDOW = 0x00040000
SW_SHOW = 5
SW_RESTORE = 9
ASFW_ANY = 0xFFFFFFFF
IGNORAR_TITULOS = {"Program Manager", "Windows Input Experience", "Experiência de Entrada do Windows",
                   "Microsoft Text Input Application", ""}
IGNORAR_EXES = {"textinputhost.exe", "shellexperiencehost.exe", "searchhost.exe", "startmenuexperiencehost.exe",
                "lockapp.exe", "navipc.exe"}


def norm(s):
    s = unicodedata.normalize("NFD", str(s).lower())
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", s)).strip()


def _exe_do_processo(pid):
    h = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        tam = wintypes.DWORD(1024)
        if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(tam)):
            return os.path.basename(buf.value).lower()
        return ""
    finally:
        kernel32.CloseHandle(h)


def _escondida(hwnd):
    """Janelas "camufladas" (apps da Store suspensos, outras áreas de trabalho)."""
    if dwmapi is None:
        return False
    val = ctypes.c_int(0)
    try:
        dwmapi.DwmGetWindowAttribute(hwnd, 14, ctypes.byref(val), ctypes.sizeof(val))  # DWMWA_CLOAKED
    except Exception:
        return False
    return val.value != 0


def listar():
    """Janelas principais visíveis, da mais da frente para a mais de trás."""
    if not IS_WIN:
        return []
    achadas = []

    def cb(hwnd, _lparam):
        try:
            if not hwnd or not user32.IsWindowVisible(hwnd) or user32.GetWindow(hwnd, GW_OWNER):
                return True
            ex = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            if ex & WS_EX_TOOLWINDOW and not ex & WS_EX_APPWINDOW:
                return True
            n = user32.GetWindowTextLengthW(hwnd)
            if n <= 0:
                return True
            buf = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(hwnd, buf, n + 1)
            titulo = buf.value
            if titulo in IGNORAR_TITULOS or _escondida(hwnd):
                return True
            pid = wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            exe = _exe_do_processo(pid.value)
            if exe in IGNORAR_EXES:
                return True
            achadas.append(Janela(int(hwnd), titulo, exe, pid.value))
        except Exception:
            pass
        return True

    proc = WNDENUMPROC(cb)  # guardar a referência até o fim da chamada
    user32.EnumWindows(proc, 0)
    return achadas


PALAVRAS_VAZIAS = {"app", "aplicativo", "programa", "jogo", "the", "de", "do", "da", "dos", "das", "o", "a",
                   "para", "pro", "pra", "no", "na", "meu", "minha", "abre", "abrir", "pc"}


def combina(j, pista):
    """A janela parece ser do programa pedido?"""
    p = norm(pista)
    if not p:
        return False
    alvo = norm(j.titulo) + " " + norm(os.path.splitext(j.exe)[0])
    if p in alvo:
        return True
    palavras = [w for w in p.split() if len(w) >= 3 and w not in PALAVRAS_VAZIAS]
    return bool(palavras) and any(w in alvo.split() or w in alvo for w in palavras)


def _entrada_falsa():
    """Um "movimento de mouse" de 0 pixels: conta como interação e libera trocar a janela da frente."""
    i = INPUT(type=0, u=_INPUTUNION(mi=MOUSEINPUT(0, 0, 0, 0x0001, 0, 0)))  # MOUSEEVENTF_MOVE
    user32.SendInput(1, ctypes.byref(i), ctypes.sizeof(INPUT))


def liberar_frente():
    """Chamar ANTES de abrir algo: deixa o programa aberto se colocar na frente sozinho."""
    if not IS_WIN:
        return
    try:
        _entrada_falsa()
        user32.AllowSetForegroundWindow(ASFW_ANY)
    except Exception:
        pass


def frente(hwnd):
    """Traz a janela para a frente. Devolve True se ela ficou na frente."""
    if not IS_WIN or not hwnd or not user32.IsWindow(hwnd):
        return False
    try:
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        else:
            user32.ShowWindow(hwnd, SW_SHOW)
        _entrada_falsa()
        if user32.SetForegroundWindow(hwnd) and user32.GetForegroundWindow() == hwnd:
            return True
        # Plano B: "grudar" no programa que está na frente agora e passar a vez.
        atual = user32.GetForegroundWindow()
        t_atual = user32.GetWindowThreadProcessId(atual, None) if atual else 0
        t_meu = kernel32.GetCurrentThreadId()
        if t_atual and t_atual != t_meu:
            user32.AttachThreadInput(t_meu, t_atual, True)
            try:
                user32.BringWindowToTop(hwnd)
                user32.SetForegroundWindow(hwnd)
            finally:
                user32.AttachThreadInput(t_meu, t_atual, False)
        if user32.GetForegroundWindow() == hwnd:
            return True
        # Plano C: o truque clássico da tecla ALT.
        user32.keybd_event(0x12, 0, 0, 0)
        user32.SetForegroundWindow(hwnd)
        user32.keybd_event(0x12, 0, 2, 0)
        return user32.GetForegroundWindow() == hwnd
    except Exception:
        return False


def achar(pista):
    """Primeira janela (mais da frente) que combina com o nome."""
    for j in listar():
        if combina(j, pista):
            return j
    return None


def esperar_nova(antes, pista=None, exes=None, segundos=8.0):
    """Espera aparecer uma janela nova (ou do programa pedido) depois de abrir algo."""
    inicio = time.time()
    fim = inicio + segundos
    exes = {e.lower() for e in (exes or [])}
    while time.time() < fim:
        atuais = listar()
        novas = [j for j in atuais if j.hwnd not in antes]
        for j in novas:
            if (pista and combina(j, pista)) or (exes and j.exe in exes):
                return j
        if novas and not pista and not exes:
            return novas[0]
        # Programa que já estava aberto: o Windows só o ativa (sem janela nova).
        if time.time() - inicio > 1.5:
            for j in atuais:
                if (pista and combina(j, pista)) or (exes and j.exe in exes):
                    return j
            if novas and time.time() - inicio > 4:
                return novas[0]
        time.sleep(0.25)
    return None


def fechar(hwnd):
    """Pede para a janela fechar (como clicar no X)."""
    if IS_WIN and hwnd:
        user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE
