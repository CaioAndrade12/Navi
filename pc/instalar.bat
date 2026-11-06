@echo off
REM Roda o navi-pc direto pelo Python (alternativa ao NaviPC.exe da página de Releases).
REM Precisa do Python 3.10+ instalado (python.org, marque "Add python.exe to PATH").
cd /d "%~dp0"
echo Instalando os pacotes do navi-pc...
python -m pip install --user -r requirements.txt
echo.
echo Iniciando o navi-pc e configurando para abrir junto com o Windows...
start "" pythonw navi_pc.py --instalar
echo Pronto! Procure o icone da Navi perto do relogio.
pause
