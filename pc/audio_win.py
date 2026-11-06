"""Som do Windows pela API Core Audio, só com ctypes (sem pycaw/comtypes).

- Volume: volume e mudo da saída de som (ou do microfone) padrão.
- dispositivos / usar: listar e trocar a saída de som (fone, caixa, TV) ou o microfone.
- apps / app_volume: volume de cada programa (Spotify, Discord, jogo...), como no Mixer de Volume.

Funciona igual rodando pelo Python ou empacotado no NaviPC.exe.
"""

import ctypes
import os
import sys
import uuid

IS_WIN = sys.platform == "win32"


class GUID(ctypes.Structure):
    _fields_ = [("Data1", ctypes.c_uint32), ("Data2", ctypes.c_uint16), ("Data3", ctypes.c_uint16),
                ("Data4", ctypes.c_ubyte * 8)]


class PROPERTYKEY(ctypes.Structure):
    _fields_ = [("fmtid", GUID), ("pid", ctypes.c_uint32)]


class PROPVARIANT(ctypes.Structure):
    # 16 bytes no Windows 32 bits, 24 no 64 bits (o maior membro da união tem 2 ponteiros).
    _fields_ = [("vt", ctypes.c_ushort), ("r1", ctypes.c_ushort), ("r2", ctypes.c_ushort), ("r3", ctypes.c_ushort),
                ("p1", ctypes.c_void_p), ("p2", ctypes.c_void_p)]


def _guid(texto):
    u = uuid.UUID(texto)
    g = GUID()
    g.Data1, g.Data2, g.Data3 = u.fields[0], u.fields[1], u.fields[2]
    for i, b in enumerate(u.bytes[8:]):
        g.Data4[i] = b
    return g


CLSID_MMDeviceEnumerator = _guid("BCDE0395-E52F-467C-8E3D-C4579291692E")
IID_IMMDeviceEnumerator = _guid("A95664D2-9614-4F35-A746-DE8DB63617E6")
IID_IAudioEndpointVolume = _guid("5CDF2C82-841E-4546-9722-0CF74078229A")
IID_IAudioSessionManager2 = _guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F")
IID_IAudioSessionControl2 = _guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D")
IID_ISimpleAudioVolume = _guid("87CE5498-68D6-44E5-9215-6DA47EF883D8")
# Interface não documentada (mas estável desde o Windows 7) que o próprio painel de Som usa
# para escolher o dispositivo padrão.
CLSID_PolicyConfigClient = _guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")
IID_IPolicyConfig = _guid("F8679F50-850A-41CF-9C72-430F290290C8")
PKEY_Device_FriendlyName = PROPERTYKEY(_guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), 14)

CLSCTX_ALL = 0x17
SAIDA, MICROFONE = 0, 1  # EDataFlow: eRender, eCapture
DEVICE_STATE_ACTIVE = 1
VT_LPWSTR = 31

if IS_WIN:
    from ctypes import wintypes

    ole32 = ctypes.WinDLL("ole32")
    ole32.CoInitializeEx.argtypes = [ctypes.c_void_p, wintypes.DWORD]
    ole32.CoInitializeEx.restype = ctypes.c_long
    ole32.CoUninitialize.argtypes = []
    ole32.CoUninitialize.restype = None
    ole32.CoCreateInstance.argtypes = [ctypes.POINTER(GUID), ctypes.c_void_p, wintypes.DWORD,
                                       ctypes.POINTER(GUID), ctypes.POINTER(ctypes.c_void_p)]
    ole32.CoCreateInstance.restype = ctypes.c_long
    ole32.CoTaskMemFree.argtypes = [ctypes.c_void_p]
    ole32.CoTaskMemFree.restype = None
    ole32.PropVariantClear.argtypes = [ctypes.POINTER(PROPVARIANT)]
    ole32.PropVariantClear.restype = ctypes.c_long


def _ok(hr, oque, nao_achou="Nenhuma saída de som encontrada no PC."):
    if hr < 0:
        codigo = hr & 0xFFFFFFFF
        if codigo == 0x80070490:
            raise OSError(nao_achou)
        raise OSError(f"{oque} falhou (0x{codigo:08X})")
    return hr


def _metodo(obj, indice, *argtypes):
    """Função número `indice` da tabela virtual de um objeto COM."""
    tabela = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    prototipo = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, *argtypes)
    funcao = prototipo(tabela[indice])
    return lambda *args: funcao(obj, *args)


def _soltar(obj):
    try:
        if obj:
            _metodo(obj, 2)()  # IUnknown::Release
    except Exception:
        pass


def _pedir(obj, iid):
    """IUnknown::QueryInterface. Devolve o ponteiro novo (ou None)."""
    out = ctypes.c_void_p()
    hr = _metodo(obj, 0, ctypes.POINTER(GUID), ctypes.POINTER(ctypes.c_void_p))(ctypes.byref(iid), ctypes.byref(out))
    return out if hr >= 0 and out else None


def _ativar(dev, iid, oque):
    """IMMDevice::Activate."""
    out = ctypes.c_void_p()
    _ok(_metodo(dev, 3, ctypes.POINTER(GUID), wintypes.DWORD, ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p))(
        ctypes.byref(iid), CLSCTX_ALL, None, ctypes.byref(out)), oque)
    return out


class _Com:
    """Liga o COM nesta thread e abre o "enumerador" de dispositivos de som."""

    def __enter__(self):
        if not IS_WIN:
            raise OSError("Só funciona no Windows.")
        hr = ole32.CoInitializeEx(None, 0x2)  # COINIT_APARTMENTTHREADED
        self._desinicializar = hr in (0, 1)  # S_OK ou S_FALSE
        self.enum = ctypes.c_void_p()
        try:
            _ok(ole32.CoCreateInstance(ctypes.byref(CLSID_MMDeviceEnumerator), None, CLSCTX_ALL,
                                       ctypes.byref(IID_IMMDeviceEnumerator), ctypes.byref(self.enum)),
                "Abrir o áudio do Windows")
        except Exception:
            self.__exit__(None, None, None)
            raise
        return self

    def __exit__(self, *_):
        _soltar(self.enum)
        self.enum = None
        if self._desinicializar:
            self._desinicializar = False
            ole32.CoUninitialize()

    def padrao(self, fluxo=SAIDA):
        """IMMDevice padrão (quem chamar solta com _soltar)."""
        dev = ctypes.c_void_p()
        papel = 1 if fluxo == SAIDA else 0  # eMultimedia para o som, eConsole para o microfone
        _ok(_metodo(self.enum, 4, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p))(
            fluxo, papel, ctypes.byref(dev)), "Achar o dispositivo padrão",
            "Nenhum microfone encontrado no PC." if fluxo == MICROFONE else "Nenhuma saída de som encontrada no PC.")
        return dev

    def ativos(self, fluxo=SAIDA):
        """Lista de IMMDevice ativos (quem chamar solta cada um)."""
        col = ctypes.c_void_p()
        _ok(_metodo(self.enum, 3, ctypes.c_int, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p))(
            fluxo, DEVICE_STATE_ACTIVE, ctypes.byref(col)), "Listar os dispositivos de som")
        out = []
        try:
            n = ctypes.c_uint()
            _ok(_metodo(col, 3, ctypes.POINTER(ctypes.c_uint))(ctypes.byref(n)), "Contar os dispositivos")
            for i in range(n.value):
                dev = ctypes.c_void_p()
                if _metodo(col, 4, ctypes.c_uint, ctypes.POINTER(ctypes.c_void_p))(i, ctypes.byref(dev)) >= 0 and dev:
                    out.append(dev)
        finally:
            _soltar(col)
        return out


def _id(dev):
    p = ctypes.c_void_p()
    _ok(_metodo(dev, 5, ctypes.POINTER(ctypes.c_void_p))(ctypes.byref(p)), "Ler o id do dispositivo")
    try:
        return ctypes.wstring_at(p.value)
    finally:
        ole32.CoTaskMemFree(p)


def _nome(dev):
    store = ctypes.c_void_p()
    if _metodo(dev, 4, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p))(0, ctypes.byref(store)) < 0 or not store:
        return ""
    try:
        pv = PROPVARIANT()
        hr = _metodo(store, 5, ctypes.POINTER(PROPERTYKEY), ctypes.POINTER(PROPVARIANT))(
            ctypes.byref(PKEY_Device_FriendlyName), ctypes.byref(pv))
        if hr < 0:
            return ""
        try:
            return ctypes.wstring_at(pv.p1) if pv.vt == VT_LPWSTR and pv.p1 else ""
        finally:
            ole32.PropVariantClear(ctypes.byref(pv))
    finally:
        _soltar(store)


class Volume:
    """Uso: with Volume() as v: v.definir(30); print(v.nivel())   (Volume(MICROFONE) para o microfone)"""

    def __init__(self, fluxo=SAIDA):
        self.fluxo = fluxo

    def __enter__(self):
        self._com = _Com().__enter__()
        self._v = None
        dev = None
        try:
            dev = self._com.padrao(self.fluxo)
            self._v = _ativar(dev, IID_IAudioEndpointVolume, "Abrir o controle de volume")
        except Exception:
            _soltar(dev)
            self.__exit__(None, None, None)
            raise
        _soltar(dev)  # solta os objetos intermediários ANTES de qualquer CoUninitialize
        return self

    def __exit__(self, *_):
        _soltar(self._v)
        self._v = None
        if self._com:
            self._com.__exit__(None, None, None)
            self._com = None

    # Ordem da tabela de IAudioEndpointVolume: 7 SetMasterVolumeLevelScalar,
    # 9 GetMasterVolumeLevelScalar, 14 SetMute, 15 GetMute.
    def nivel(self):
        f = ctypes.c_float()
        _ok(_metodo(self._v, 9, ctypes.POINTER(ctypes.c_float))(ctypes.byref(f)), "Ler o volume")
        return int(round(f.value * 100))

    def definir(self, porcentagem):
        alvo = max(0.0, min(1.0, float(porcentagem) / 100.0))
        _ok(_metodo(self._v, 7, ctypes.c_float, ctypes.c_void_p)(alvo, None), "Mudar o volume")

    def mudo(self):
        b = wintypes.BOOL()
        _ok(_metodo(self._v, 15, ctypes.POINTER(wintypes.BOOL))(ctypes.byref(b)), "Ler o mudo")
        return bool(b.value)

    def definir_mudo(self, ligado):
        _ok(_metodo(self._v, 14, wintypes.BOOL, ctypes.c_void_p)(1 if ligado else 0, None), "Mudar o mudo")


# ---------------------------------------------------------------- dispositivos

def dispositivos(fluxo=SAIDA):
    """[{id, nome, padrao}] das saídas de som (ou microfones) ligadas."""
    with _Com() as com:
        try:
            dev = com.padrao(fluxo)
            try:
                id_padrao = _id(dev)
            finally:
                _soltar(dev)
        except OSError:
            id_padrao = None
        lista = []
        for dev in com.ativos(fluxo):
            try:
                i = _id(dev)
                lista.append({"id": i, "nome": _nome(dev) or "Dispositivo sem nome", "padrao": i == id_padrao})
            finally:
                _soltar(dev)
        return lista


def usar(id_dispositivo):
    """Torna o dispositivo o padrão para tudo (jogos, música, chamadas)."""
    with _Com():
        pc = ctypes.c_void_p()
        _ok(ole32.CoCreateInstance(ctypes.byref(CLSID_PolicyConfigClient), None, CLSCTX_ALL,
                                   ctypes.byref(IID_IPolicyConfig), ctypes.byref(pc)), "Abrir as opções de som")
        try:
            # IPolicyConfig::SetDefaultEndpoint é a 14ª função (índice 13) da tabela.
            trocar = _metodo(pc, 13, wintypes.LPCWSTR, ctypes.c_int)
            for papel in (0, 1, 2):  # eConsole, eMultimedia, eCommunications
                _ok(trocar(id_dispositivo, papel), "Trocar o dispositivo padrão")
        finally:
            _soltar(pc)


# ---------------------------------------------------------------- volume por programa

def _exe(pid):
    if not pid:
        return "sons do sistema"
    try:
        import janelas
        nome = janelas._exe_do_processo(pid)
        if nome:
            return os.path.splitext(nome)[0]
    except Exception:
        pass
    return f"processo {pid}"


def _sessoes(com):
    """[(exe, pid, ISimpleAudioVolume)] de todos os programas com som, em todas as saídas."""
    out = []
    for dev in com.ativos(SAIDA):
        mgr = en = None
        try:
            mgr = _ativar(dev, IID_IAudioSessionManager2, "Abrir o mixer de volume")
            en = ctypes.c_void_p()
            _ok(_metodo(mgr, 5, ctypes.POINTER(ctypes.c_void_p))(ctypes.byref(en)), "Listar os programas com som")
            n = ctypes.c_int()
            _ok(_metodo(en, 3, ctypes.POINTER(ctypes.c_int))(ctypes.byref(n)), "Contar os programas")
            for i in range(n.value):
                ctl = ctypes.c_void_p()
                if _metodo(en, 4, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p))(i, ctypes.byref(ctl)) < 0 or not ctl:
                    continue
                ctl2 = vol = None
                try:
                    estado = ctypes.c_int()
                    _metodo(ctl, 3, ctypes.POINTER(ctypes.c_int))(ctypes.byref(estado))
                    if estado.value == 2:  # sessão encerrada
                        continue
                    ctl2 = _pedir(ctl, IID_IAudioSessionControl2)
                    pid = wintypes.DWORD(0)
                    if ctl2:
                        _metodo(ctl2, 14, ctypes.POINTER(wintypes.DWORD))(ctypes.byref(pid))  # GetProcessId
                    vol = _pedir(ctl, IID_ISimpleAudioVolume)
                    if vol:
                        out.append((_exe(pid.value), pid.value, vol))
                        vol = None  # quem recebe a lista solta
                finally:
                    _soltar(vol)
                    _soltar(ctl2)
                    _soltar(ctl)
        except OSError:
            continue
        finally:
            _soltar(en)
            _soltar(mgr)
            _soltar(dev)
    return out


def _ler(vol):
    f, b = ctypes.c_float(), wintypes.BOOL()
    _metodo(vol, 4, ctypes.POINTER(ctypes.c_float))(ctypes.byref(f))  # GetMasterVolume
    _metodo(vol, 6, ctypes.POINTER(wintypes.BOOL))(ctypes.byref(b))  # GetMute
    return int(round(f.value * 100)), bool(b.value)


def apps():
    """{programa: {"volume": n, "mudo": b}} de quem está usando o som agora."""
    with _Com() as com:
        sessoes = _sessoes(com)
        try:
            out = {}
            for exe, pid, vol in sessoes:
                n, m = _ler(vol)
                out.setdefault(exe, {"volume": n, "mudo": m, "pid": pid})
            return out
        finally:
            for _, _, vol in sessoes:
                _soltar(vol)


def app_volume(escolher, nivel=None, passo=None, mudo=None):
    """Muda o volume (0-100), soma `passo` ou liga/desliga o mudo dos programas que `escolher(exe, pid)` aprovar.

    Devolve {programa: {"volume": n, "mudo": b}} depois da mudança.
    """
    with _Com() as com:
        sessoes = _sessoes(com)
        try:
            out = {}
            for exe, pid, vol in sessoes:
                if not escolher(exe, pid):
                    continue
                atual, _ = _ler(vol)
                alvo = atual
                if nivel is not None:
                    alvo = nivel
                elif passo is not None:
                    alvo = atual + passo
                alvo = max(0, min(100, int(alvo)))
                if alvo != atual:
                    _metodo(vol, 3, ctypes.c_float, ctypes.c_void_p)(alvo / 100.0, None)  # SetMasterVolume
                if mudo is not None:
                    _metodo(vol, 5, wintypes.BOOL, ctypes.c_void_p)(1 if mudo else 0, None)  # SetMute
                elif nivel is not None or (passo or 0) > 0:
                    _metodo(vol, 5, wintypes.BOOL, ctypes.c_void_p)(0, None)  # quem aumenta quer ouvir
                out[exe] = dict(zip(("volume", "mudo"), _ler(vol)))
            return out
        finally:
            for _, _, vol in sessoes:
                _soltar(vol)
