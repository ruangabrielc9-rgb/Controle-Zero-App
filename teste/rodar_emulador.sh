#!/usr/bin/env bash
set -u
pip install --quiet websocket-client
python3 teste/emulador.py CatalogoZero.apk teste/saida
