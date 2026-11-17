# Monta o pacote do Windows (STOX3-para-Windows.zip) a partir do app/src/main/assets/index.html.
# Uso:  GD_SECRET=... python3 tools/montar_windows.py PASTA_DO_PACOTE_ANTIGO SAIDA.zip
#   PASTA_DO_PACOTE_ANTIGO = a pasta STOX3 do zip anterior (traz os .bat, o icone, o LEIA-ME e os dados da planilha).
# O segredo do Google e os dados da planilha so entram no zip: nunca neste repositorio.
import json, os, re, sys, zipfile

src = 'app/src/main/assets/index.html'
old_dir, out = sys.argv[1], sys.argv[2]
sec = os.environ.get('GD_SECRET', '').strip()
if not sec:
    sys.exit('Falta o GD_SECRET.')

s = open(src, encoding='utf-8').read()
old = open(os.path.join(old_dir, 'Carteira.html'), encoding='utf-8').read()

# 1) no computador o aviso de importar a planilha continua valendo (no Android ele e desligado)
line = "try{if(localStorage.getItem('carteira-imp-v2')===null)localStorage.setItem('carteira-imp-v2','1');if(localStorage.getItem('carteira-imp-v3')===null)localStorage.setItem('carteira-imp-v3','1')}catch(e){}\n"
assert s.count(line) == 1, 'linha da planilha'
s = s.replace(line, '')

# 2) backup em arquivo: no computador baixa o arquivo (sem a ponte do Android)
s, n = re.subn(r"\.json';if\(window\.AndroidFiles\)\{.*?return\}var a=document\.createElement", ".json',a=document.createElement", s, count=1, flags=re.S)
assert n == 1, 'backup'

# 3) dados da planilha do pacote anterior
m = re.search(r'var IMP=(\{.*?\});\(function\(\)\{var b', old, flags=re.S)
assert m, 'dados da planilha no pacote antigo'
s, n = re.subn(r'var IMP=\{"ops":\[\],"prov":\[\],"carry":\{\},"quotes":\{\}\};', lambda _: 'var IMP=' + m.group(1) + ';', s, count=1)
assert n == 1, 'IMP'

# 4) segredo do Google
tok = "'@@GD_SECRET@@'"
assert s.count(tok) == 1, 'segredo'
s = s.replace(tok, json.dumps(sec))

ver = re.search(r"APPV='([^']+)'", s).group(1)
z = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
z.writestr('STOX3/Carteira.html', s.encode('utf-8'))
for f in ['Abrir Carteira.bat', 'Criar atalho na Area de Trabalho.bat', 'icone.ico', 'LEIA-ME.txt']:
    z.write(os.path.join(old_dir, f), 'STOX3/' + f)
z.close()
print('Pacote', ver, 'pronto:', out)
