# Navi ✦

Assistente de voz pessoal que mora no celular Android. Diga **"Navi"** e fale normalmente:
ela responde em voz alta, controla a **TV Samsung** e o **PC Windows**, lê e manda mensagens
(**WhatsApp, Instagram, Gmail**) e conversa sobre qualquer assunto.

## O que dá para pedir

| Você diz | A Navi faz |
|---|---|
| "Navi, liga a TV e abre a Netflix" | Liga a TV (rede ou infravermelho) e abre o app |
| "Coloca no HDMI 2" / "aumenta o volume da TV" | Troca a entrada, ajusta volume, aperta teclas |
| "Lê minhas mensagens" / "o que o João mandou?" | Lê em voz alta as mensagens que chegaram |
| "Responde que eu chego em 10 minutos" | Responde direto pela notificação (até bloqueado) |
| "Manda no WhatsApp pra minha mãe: tô saindo" | Acha o contato e envia (pergunta antes de mandar) |
| "Tenho e-mail novo?" / "manda um e-mail pro Pedro" | Lê e envia pelo Gmail |
| "Coloca o volume do PC em 30" / "abre o Spotify no PC" | Controla o Windows pelo agente navi-pc |
| "Ativa o modo escuro do PC" / "desliga o PC daqui a 20 minutos" | Ajustes e energia do PC |
| "Como está o tempo amanhã?" / "quem ganhou o jogo?" | Pesquisa no Google e responde |
| "Lembra que o HDMI 2 é o videogame" | Guarda para sempre e usa nas próximas conversas |
| "Me acorda às 7" / "toca Charlie Brown Jr" / "liga a lanterna" | Ações no próprio celular |

Depois de responder, ela continua ouvindo por alguns segundos, então dá para conversar sem repetir "Navi".

## Como funciona

```
 "Navi" (offline, Vosk) ──> ouve o pedido (pt-BR) ──> Gemini decide o que fazer ──> fala a resposta
                                                         │
             ┌───────────────────────────┬───────────────┼────────────────────┬──────────────────┐
        TV Samsung                   PC Windows      Notificações         WhatsApp           Gmail
   (Wi-Fi: WebSocket 8001/8002,   (navi-pc na rede,  (lê e responde     (abre a conversa   (IMAP/SMTP com
    Wake-on-LAN; IR do celular)    porta 8765)        WhatsApp/Insta)     e aperta Enviar)   senha de app)
```

- **Cérebro:** Gemini API (modelo padrão `gemini-flash-latest`, com reserva automática no `gemini-3.5-flash-lite`).
- **Segurança:** veja a seção *Segurança e privacidade* abaixo.
- **Sem chave do Gemini**, a Navi faz como antes: abre o app do Gemini quando ouve "Navi".

## 1. Instalar no celular

O repositório é **privado**, então o download precisa de um token do GitHub (só leitura):

1. No navegador, logado no GitHub: **github.com/settings/personal-access-tokens/new**
   - *Token name:* `navi` · *Expiration:* o que preferir
   - *Repository access:* **Only select repositories** → `Navi`
   - *Permissions → Repository permissions → Contents:* **Read-only**
   - **Generate token** e copie (começa com `github_pat_`).
2. Instale o **Termux** pelo F-Droid (a versão da Play Store é desatualizada).
3. Abra o Termux, cole o comando abaixo e, quando pedir, cole o token:

```
pkg install -y curl jq && read -p "Token do GitHub: " T && echo "$T" > ~/.navi_token && curl -fsSL -H "Authorization: Bearer $T" -H "Accept: application/vnd.github.raw" https://api.github.com/repos/CaioAndrade12/Navi/contents/termux/setup.sh -o setup.sh && bash setup.sh
```

O script pareia com a Depuração sem fio, baixa e instala o APK e libera microfone, contatos,
notificações, acessibilidade, sobreposição e bateria. **Para atualizar depois:** `bash setup.sh`.

Se a chave de assinatura do app mudou (aconteceu na versão de segurança), o script remove a versão
antiga sozinho; aí é só colar a chave do Gemini de novo.

**Alternativa sem Termux:** logado no GitHub, baixe o `Navi.apk` em *Releases* e instale
(desinstale a versão antiga antes). Se o Android disser que acessibilidade/notificações são
"configurações restritas": Configurações > Apps > Navi > ⋮ > *Permitir configurações restritas*.

## 2. Configurar (tela Ajustes do app)

1. **Chave do Gemini:** toque em *Pegar chave grátis*, crie a chave no Google AI Studio e cole.
   No plano grátis o Google pode usar as conversas para melhorar os produtos; se preferir, ative o
   faturamento no AI Studio (aí os dados não são usados).
2. **TV:** com a TV ligada no mesmo Wi-Fi, *Procurar TV* > *Testar* e **aceite o pedido na tela da TV**.
   Para ligar a TV pela rede, ative na TV *Configurações > Geral > Rede > Configurações especialistas >
   Ligar com celular*. Sem isso, a Navi usa o infravermelho do Poco (aponte para a TV).
3. **PC:** instale o navi-pc (abaixo). No PC, clique no ícone ✦ perto do relógio > *Parear um celular*;
   no celular, *Procurar PC*, digite o código de 8 dígitos e *Parear*.
4. **Privacidade e acesso:** desligue o que a Navi não pode usar (WhatsApp, Instagram, Gmail, TV, PC...).
5. **Gmail (opcional):** ative a verificação em duas etapas, crie uma *senha de app* (botão no app) e cole
   com o seu e-mail. Ative o IMAP no Gmail (Configurações > Encaminhamento e POP/IMAP).

## 3. Instalar no PC (Windows)

1. Logado no GitHub, baixe o **`NaviPC.exe`** em *Releases* e abra. Se o Windows perguntar,
   permita na **rede privada** (se o SmartScreen avisar: *Mais informações > Executar assim mesmo*).
2. Na primeira vez aparece o **endereço** e um **código de 8 dígitos** (vale por 10 minutos).
   Depois, para parear de novo: ícone ✦ perto do relógio > **Parear um celular**.
3. No ícone, escolha **Iniciar com o Windows**.

Prefere rodar pelo código? Com o Python 3.10+ instalado, abra `pc/instalar.bat`.
Ajustes ficam em `%APPDATA%\NaviPC\config.json`. No ícone ✦ dá para bloquear o PowerShell e
desparear todos os celulares.
Para ligar o PC pelo celular ("Navi, liga o PC"), ative o Wake-on-LAN na BIOS e na placa de rede.

## Segurança e privacidade

- **Confirmação no código:** mandar mensagem, e-mail, ligar, fechar programas, desligar o PC e
  PowerShell só acontecem depois do seu "sim". Desligar o PC e PowerShell pedem confirmação sempre.
- **Contra instruções escondidas:** se a Navi acabou de ler uma mensagem, e-mail ou página, qualquer
  ação no PC, envio de mensagem ou memória nova exige confirmação, mesmo com as confirmações desligadas.
- **Celular bloqueado:** ninguém consegue ouvir suas mensagens, e-mails ou mexer no seu PC pedindo
  para a Navi; ao bloquear, ela esquece a conversa. (Pode desligar em *Privacidade e acesso*.)
- **PC:** pareamento com código temporário de 8 dígitos; cada celular recebe uma chave de 256 bits e
  todo pedido vai assinado (HMAC) com horário e número único, então copiar o tráfego do Wi-Fi não
  permite repetir comandos. O PC não abre executáveis nem caminhos de rede pedidos pela IA.
- **Chaves guardadas:** Gemini, Gmail e PC ficam criptografados com o Android Keystore.
- **Mensagens:** guardadas só por 48 horas; apps desligados não são lidos nem guardados.
- **Chave de assinatura do APK:** a antiga ficou pública enquanto o repositório era aberto. Para trocar
  (uma vez, no PC): rode `powershell -ExecutionPolicy Bypass -File tools\nova-chave-assinatura.ps1`,
  crie os dois segredos que ele indicar em *Settings > Secrets and variables > Actions* e rode o build
  de novo (aba *Actions* > *Run workflow*). No celular, `bash setup.sh` troca a versão sozinho.
- **Plano grátis do Gemini:** o Google pode usar as conversas para melhorar os produtos. Desligue os
  apps que não quer que passem pela IA, ou ative o faturamento no AI Studio.

## Ensinar coisas novas

- **Pela voz:** "Navi, lembra que..." (ela guarda em *O que a Navi lembra*).
- **No PC:** crie uma função com `@tool(...)` em `pc/acoes.py`. O celular aprende sozinho na próxima vez
  que parear (ou ao ligar a Navi), sem recompilar o app.
- **No celular:** as ferramentas ficam em `Toolbox.kt`.

## Arquivos

| Arquivo | O que faz |
|---|---|
| `NaviService.kt` | Escuta "Navi", conduz a conversa (ouvir → pensar → falar) |
| `Brain.kt` / `Gemini.kt` | Cérebro: histórico, ferramentas, confirmações, memória |
| `Toolbox.kt` | Todas as ações que a Navi sabe fazer |
| `SamsungTv.kt` / `IrRemote.kt` | TV pela rede e pelo infravermelho |
| `PcClient.kt` + `pc/` | Conversa com o agente do Windows |
| `NaviNotificationService.kt` / `WhatsApp.kt` / `Email.kt` | Mensagens e e-mails |
| `Listener.kt` / `Speaker.kt` | Reconhecimento de fala e voz |
| `MainActivity.kt` / `SettingsActivity.kt` | Tela de conversa e ajustes |
| `.github/workflows/build.yml` | Compila o APK e o NaviPC.exe e publica em *Releases* |

## Se algo não funcionar

- **Não ouve "Navi":** em Ajustes, adicione variações (ex.: `navi, nave, navy`) e salve.
- **Ouve mas não entende o pedido:** confira se o app Google está instalado e atualizado (é ele que
  transcreve a fala). Se não funcionar, a Navi passa a mandar o áudio direto para o Gemini.
- **TV não conecta:** na TV, *Geral > Gerenciador de dispositivos externos > Gerenciador de conexão de
  dispositivos*: permita a Navi e deixe a notificação de acesso ligada.
- **PC não aparece:** o navi-pc precisa estar aberto e liberado no Firewall do Windows (rede privada).
- **Para de funcionar depois de um tempo:** ative "Inicialização automática" e trave o app na tela de
  recentes (cadeado).

## Voltar para a versão antiga (só abria o Gemini)

A versão que só abria o Gemini é o commit `fe331f5` (`git checkout fe331f5`). Sem mexer no código,
dá para voltar a esse comportamento desmarcando em Ajustes *"A própria Navi responde"*.

> A chave de assinatura nova fica só nos segredos do GitHub, nunca no código. Suas chaves
> (Gemini, Gmail, PC) ficam só no celular, criptografadas.
