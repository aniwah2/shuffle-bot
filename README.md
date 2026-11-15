# Shuffle Bot (Android 11+)

App que joga Pokémon Shuffle sozinho: tira print da tela, reconhece as peças por cor,
calcula a melhor troca (o Shuffle deixa arrastar para qualquer casa) e faz o arrasto.

## Gerar o APK

**Opção A — sem instalar nada (GitHub):**
1. Crie um repositório no GitHub e envie esta pasta inteira (pode arrastar os arquivos pelo site, incluindo a pasta `.github`).
2. Abra a aba **Actions** → "Gerar APK" → aguarde ~3 min.
3. Clique na execução concluída e baixe **ShuffleBot-apk** (vem um .zip com o `app-debug.apk`).

**Opção B — Android Studio:** File › Open › esta pasta › Build › Build APK(s).

## Instalar e usar
1. Passe o `.apk` para o celular e instale (permita "fontes desconhecidas").
2. Abra o Shuffle Bot → "Ativar serviço" → ligue em Acessibilidade.
   Android 13+: se estiver cinza, Configurações › Apps › Shuffle Bot › ⋮ › "Permitir configurações restritas".
3. No jogo, toque em **Calibrar**, encaixe a grade verde no tabuleiro 6x6, toque em **Salvar grade**.
4. Deixe o painel fora do tabuleiro e toque em **▶ Iniciar**.

## Se algo não funcionar
- **Arrasta mas a peça não sai do lugar:** aumente "Segurar" (ex. 120) e "Duração do arrasto" (ex. 300), ou ponha Segurar = 0.
- **Painel mostra mais tipos do que existem:** aumente a tolerância (ex. 35–40).
- **Confunde Pokémon parecidos:** diminua a tolerância (ex. 20).
- **Fases de tempo:** desmarque "Esperar as peças pararem" e baixe a pausa para 50–100 ms.
- Pedras, blocos e moedas: ele percebe quando uma jogada não funcionou e não repete.
