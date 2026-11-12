# Coloca o segredo do cliente Google dentro do app so na hora de compilar.
# O segredo fica guardado em GitHub > Settings > Secrets and variables > Actions > GD_SECRET e nunca entra no codigo-fonte publico.
import json, os, sys
p = 'app/src/main/assets/index.html'
tok = "'@@GD_SECRET@@'"
sec = os.environ.get('GD_SECRET', '').strip()
s = open(p, encoding='utf-8').read()
if not sec:
    print('::warning::O segredo GD_SECRET nao existe no GitHub. O app sai SEM o acesso do Google embutido (o login so funciona colando o ID e o segredo em Configuracoes > Avancado).')
    sys.exit(0)
if s.count(tok) != 1:
    print('::error::Nao achei o lugar do segredo no app.')
    sys.exit(1)
s = s.replace(tok, json.dumps(sec))
open(p, 'w', encoding='utf-8', newline='').write(s)
print('Acesso do Google embutido no app.')
