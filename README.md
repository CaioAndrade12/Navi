# Navi ✦

Assistente de voz para Android: diga **"Navi"** e o Gemini abre pronto para o seu comando
(mandar mensagem, ler mensagens, trocar música, gravar áudio e tudo que o Gemini já faz).

- Reconhece a palavra **offline**, no próprio celular (Vosk), sem conta e sem chave.
- Solta o microfone enquanto o Gemini está aberto e volta a escutar depois.
- Feito pensando no Poco F5 Pro (HyperOS); requer Android 10+.

## Instalar só pelo celular

1. Instale o **Termux** pelo F-Droid (a versão da Play Store é desatualizada).
2. Abra o Termux e cole:

```
pkg install -y curl && curl -fsSL https://raw.githubusercontent.com/CaioAndrade12/navi/main/termux/setup.sh -o setup.sh && bash setup.sh
```

O script pareia com a Depuração sem fio, baixa e instala o APK, libera microfone, notificações,
sobreposição e bateria. Para atualizar, rode `bash setup.sh` de novo.

**Alternativa:** baixe o `Navi.apk` em *Releases* e instale direto, liberando as permissões pelos
botões dentro do app.

## Como funciona

| Arquivo | O que faz |
|---|---|
| `NaviService.kt` | Serviço em primeiro plano que escuta a palavra e chama o assistente |
| `AssistLauncher.kt` | Acorda a tela e abre o Gemini (3 modos, escolha no app) |
| `MainActivity.kt` | Ligar/desligar, testar, permissões e ajustes |
| `.github/workflows/build.yml` | Compila o APK na nuvem a cada push e publica em *Releases* |

## Se algo não funcionar

- **Não ouve "Navi":** em Ajustes, adicione variações (ex.: `navi, nave, navy`) e salve.
- **Ouve mas o Gemini não abre:** confira "Sobrepor outros apps" e, na Xiaomi,
  "Exibir janelas pop-up em segundo plano". Teste os outros modos de abertura.
- **Para de funcionar depois de um tempo:** ative "Inicialização automática" e trave o app
  na tela de recentes (cadeado).

> A chave de assinatura (`app/navi.keystore`) está no repositório de propósito, para que
> cada build instale por cima da anterior. É um app pessoal; não use essa chave para nada sério.
