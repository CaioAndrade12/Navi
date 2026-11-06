#!/data/data/com.termux/files/usr/bin/bash
# Instalador da Navi — roda no próprio celular, dentro do Termux.
# Instala o app e libera as permissões sozinho, usando a Depuração sem fio.

PKG="com.caio.navi"
APK_URL="https://github.com/CaioAndrade12/navi/releases/download/apk/Navi.apk"
APK="$PREFIX/tmp/Navi.apk"

azul()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
verde() { printf '\033[1;32m%s\033[0m\n' "$*"; }
amar()  { printf '\033[1;33m%s\033[0m\n' "$*"; }
verm()  { printf '\033[1;31m%s\033[0m\n' "$*"; }

clear
azul "========================================"
azul "      Instalador da Navi  ✦"
azul "========================================"
echo

# 1) Ferramentas
if ! command -v adb >/dev/null 2>&1; then
  azul "[1/5] Instalando as ferramentas (adb)..."
  yes | pkg update -y >/dev/null 2>&1
  pkg install -y android-tools curl || { verm "Falhou ao instalar o adb. Verifique a internet."; exit 1; }
else
  azul "[1/5] Ferramentas já instaladas."
fi
echo

# 2) Conectar no próprio celular pela Depuração sem fio
azul "[2/5] Conectando no celular pela Depuração sem fio"
SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')

if [ -z "$SERIAL" ]; then
  echo
  amar "Faça assim (só na primeira vez):"
  echo "  1. Abra Configurações > Configurações adicionais > Opções do desenvolvedor"
  echo "  2. Ative 'Depuração USB (configurações de segurança)' (precisa da conta Mi)"
  echo "  3. Ative 'Depuração sem fio' (com Wi-Fi ligado) e toque nela"
  echo "  4. Toque em 'Parear dispositivo com código de pareamento'"
  echo
  amar "DICA: deixe Termux e Configurações em TELA DIVIDIDA,"
  amar "senão o código some quando você troca de app."
  echo
  read -r -p "Porta de pareamento (o número depois dos ':' na janela do código): " PPORT
  read -r -p "Código de pareamento (6 dígitos): " PCODE
  adb pair "127.0.0.1:$PPORT" "$PCODE" || { verm "Pareamento falhou. Rode o script de novo e confira porta e código."; exit 1; }
  verde "Pareado!"
  sleep 2

  # Tenta descobrir sozinho a porta de conexão
  CPORT=$(adb mdns services 2>/dev/null | grep -i "_adb-tls-connect" | grep -oE ':[0-9]+' | head -n1 | tr -d ':')
  if [ -z "$CPORT" ]; then
    echo
    echo "Agora olhe a tela principal da 'Depuração sem fio',"
    echo "em 'Endereço IP e porta' (ex.: 192.168.0.10:41235)."
    read -r -p "Digite só a porta (o número depois dos ':'): " CPORT
  fi
  adb connect "127.0.0.1:$CPORT" >/dev/null
  sleep 1
  SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi

if [ -z "$SERIAL" ]; then
  verm "Não consegui conectar. Confira se a Depuração sem fio está ligada e rode de novo."
  exit 1
fi
verde "Conectado ($SERIAL)"
A() { adb -s "$SERIAL" "$@"; }
SH() { adb -s "$SERIAL" shell "$@" >/dev/null 2>&1; }
echo

# 3) Baixar e instalar o app
azul "[3/5] Baixando a Navi..."
mkdir -p "$PREFIX/tmp"
if [ -f "$HOME/storage/downloads/Navi.apk" ]; then
  cp "$HOME/storage/downloads/Navi.apk" "$APK"
  echo "Usando o Navi.apk da pasta Downloads."
else
  curl -fL --progress-bar -o "$APK" "$APK_URL" || { verm "Não consegui baixar o APK."; exit 1; }
fi
azul "Instalando..."
if ! A install -r "$APK"; then
  verm "A instalação falhou."
  amar "Na Xiaomi, ative 'Instalar via USB' nas Opções do desenvolvedor e rode de novo."
  exit 1
fi
verde "App instalado!"
echo

# 4) Permissões e bateria
azul "[4/5] Liberando permissões..."
ok()  { verde "  ✓ $1"; }
nok() { amar  "  ! $1 (faça manualmente no app)"; }

A shell pm grant $PKG android.permission.RECORD_AUDIO >/dev/null 2>&1 && ok "Microfone" || nok "Microfone"
A shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 && ok "Notificações" || nok "Notificações"
A shell pm grant $PKG android.permission.READ_CONTACTS >/dev/null 2>&1 && ok "Contatos" || nok "Contatos"
A shell pm grant $PKG android.permission.CALL_PHONE >/dev/null 2>&1 && ok "Ligações" || nok "Ligações"

# Ler notificações (mensagens do WhatsApp, Instagram, Gmail)
A shell cmd notification allow_listener "$PKG/$PKG.NaviNotificationService" >/dev/null 2>&1 \
  && ok "Acesso às notificações" || nok "Acesso às notificações"

# Acessibilidade (apertar "Enviar" no WhatsApp) — mantém os serviços que já estavam ligados
SVC="$PKG/$PKG.NaviAccessibilityService"
CUR=$(A shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$SVC"
elif echo "$CUR" | grep -q "$SVC"; then NEW="$CUR"
else NEW="$CUR:$SVC"; fi
if A shell settings put secure enabled_accessibility_services "$NEW" >/dev/null 2>&1 \
  && A shell settings put secure accessibility_enabled 1 >/dev/null 2>&1; then
  ok "Acessibilidade"
else
  nok "Acessibilidade"
fi
A shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 && ok "Sobrepor outros apps" || nok "Sobrepor outros apps"
A shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 && ok "Sem restrição de bateria" || nok "Bateria"
SH cmd appops set $PKG RUN_IN_BACKGROUND allow
SH cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow
# Permissões próprias da Xiaomi (podem não funcionar em toda versão do HyperOS)
SH appops set $PKG 10021 allow   # exibir janelas pop-up em segundo plano
SH appops set $PKG 10008 allow   # inicialização automática
ok "Ajustes da Xiaomi (tentativa)"
echo

# 5) Abrir o app
azul "[5/5] Abrindo a Navi..."
A shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
echo
verde "========================================"
verde " Pronto! Agora, no app da Navi:"
verde "========================================"
echo " 1. Toque em 'Ajustes' e cole sua chave grátis do Gemini"
echo "    (botão 'Pegar chave grátis' abre o Google AI Studio)"
echo " 2. Com a TV ligada, toque em 'Procurar TV' e depois 'Testar'"
echo "    (aceite o pedido que aparece na tela da TV)"
echo " 3. No PC, abra o NaviPC.exe (página de Releases do GitHub)"
echo "    e pareie em 'Procurar PC' com o código que ele mostrar"
echo " 4. Gmail (opcional): seu e-mail + uma senha de app do Google"
echo " 5. Xiaomi: 'inicialização automática' e 'pop-up em 2º plano'"
echo " 6. Volte, toque em 'Ligar' e diga: 'Navi, que horas são?'"
echo
amar "Pode desligar a Depuração sem fio agora, se quiser."
amar "Para atualizar a Navi no futuro, é só rodar este script de novo."
