#!/usr/bin/env python3
"""Teste de ponta a ponta do Catalogo Zero num Android de verdade (emulador).
Controla a pagina dentro do WebView pelo protocolo DevTools e confere no aparelho:
dados que sobrevivem a fechar o app, rascunho, copia automatica em Downloads,
reinstalar o app, restaurar o arquivo e atualizar o app por cima."""
import base64, json, os, subprocess, sys, time, urllib.request, traceback
import websocket  # pip install websocket-client

PKG = "br.bancadazero.catalogo"
APK = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
resultados = []
LOG = open(os.path.join(OUT, "teste.log"), "w", encoding="utf-8")

def log(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True); LOG.write(s + "\n"); LOG.flush()

def sh(cmd, check=False, timeout=120):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{cmd}\n{r.stdout}\n{r.stderr}")
    return (r.stdout or "") + (r.stderr or "")

def ok(nome, cond, extra=""):
    resultados.append((nome, bool(cond)))
    log(("PASSOU  " if cond else "FALHOU  ") + nome + ((" -> " + str(extra)) if extra != "" else ""))

class Pagina:
    def __init__(self):
        self.ws = None; self.n = 0
    def conectar(self, tent=60):
        pid = ""
        for _ in range(tent):
            pid = sh(f"adb shell pidof {PKG}").strip().split()
            if pid:
                break
            time.sleep(1)
        if not pid:
            raise RuntimeError("app nao abriu")
        pid = pid[0]
        sh("adb forward --remove-all")
        # o socket do WebView demora alguns segundos para aparecer
        for _ in range(tent):
            socks = sh("adb shell cat /proc/net/unix")
            if f"webview_devtools_remote_{pid}" in socks:
                break
            time.sleep(1)
        sh(f"adb forward tcp:9222 localabstract:webview_devtools_remote_{pid}", check=True)
        url = None
        for _ in range(tent):
            try:
                lista = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5))
                pg = [p for p in lista if p.get("type") == "page" and "appassets" in p.get("url", "")]
                if pg:
                    url = pg[0]["webSocketDebuggerUrl"]; break
            except Exception:
                pass
            time.sleep(1)
        if not url:
            raise RuntimeError("pagina do WebView nao apareceu no DevTools")
        self.ws = websocket.create_connection(url, timeout=120, max_size=None, suppress_origin=True)
        self.pronto()
    def cdp(self, metodo, params=None):
        self.n += 1; i = self.n
        self.ws.send(json.dumps({"id": i, "method": metodo, "params": params or {}}))
        while True:
            m = json.loads(self.ws.recv())
            if m.get("id") == i:
                return m
    def ev(self, expr):
        r = self.cdp("Runtime.evaluate", {"expression": expr, "awaitPromise": True, "returnByValue": True, "userGesture": True})
        res = r.get("result", {})
        if "exceptionDetails" in res:
            raise RuntimeError("JS: " + json.dumps(res["exceptionDetails"], ensure_ascii=False)[:600])
        return res.get("result", {}).get("value")
    def esperar(self, expr, seg=40):
        t = time.time()
        while time.time() - t < seg:
            try:
                if self.ev(expr):
                    return True
            except Exception:
                pass
            time.sleep(0.4)
        return False
    def pronto(self):
        self.esperar("document.readyState==='complete'&&!!document.querySelector('#fab')&&!!document.querySelector('#main').innerHTML", 60)
        self.ev(AJUDA)
    def fechar(self):
        try:
            self.ws.close()
        except Exception:
            pass

AJUDA = r"""
window.T = {
  sleep: function(ms){ return new Promise(function(r){setTimeout(r,ms);}); },
  setv: function(sel,v){ var e=document.querySelector(sel); e.value=v; e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); },
  click: function(sel){ var e=document.querySelector(sel); if(!e) throw new Error('sem '+sel); e.click(); },
  foto: async function(w,h,seed){
    var c=document.createElement('canvas'); c.width=w; c.height=h; var x=c.getContext('2d');
    var g=x.createLinearGradient(0,0,w,h); g.addColorStop(0,'hsl('+(seed*37%360)+',60%,30%)'); g.addColorStop(1,'hsl('+(seed*91%360)+',70%,55%)');
    x.fillStyle=g; x.fillRect(0,0,w,h);
    for(var i=0;i<60;i++){ x.fillStyle='hsl('+((seed*13+i*29)%360)+',50%,'+(20+i%50)+'%)'; x.fillRect((i*131+seed*17)%w,(i*71+seed*29)%h,w/12,h/14); }
    x.fillStyle='#fff'; x.font=(h/10)+'px sans-serif'; x.fillText('PM '+seed,w/10,h/2);
    var blob=await new Promise(function(r){c.toBlob(r,'image/jpeg',0.92);});
    var f=new File([blob],'foto'+seed+'.jpg',{type:'image/jpeg'});
    var dt=new DataTransfer(); dt.items.add(f);
    var inp=document.querySelector('#fFile'); inp.files=dt.files; inp.dispatchEvent(new Event('change',{bubbles:true}));
    for(var k=0;k<100;k++){ await T.sleep(100); if(!document.querySelector('#fSave').disabled) break; }
    return blob.size;
  },
  novoItem: async function(nome,extra){
    T.click('#fab'); await T.sleep(250);
    T.setv('#fNome',nome);
    if(extra&&extra.codigo) T.setv('#fCodigo',extra.codigo);
    if(extra&&extra.obs) T.setv('#fObs',extra.obs);
    if(extra&&extra.foto) await T.foto(3000,2000,extra.foto);
    T.click('#fSave');
    for(var k=0;k<100;k++){ await T.sleep(100); if(document.querySelector('#sheetForm').hidden) break; }
    return document.querySelector('#sheetForm').hidden;
  },
  idb: function(){
    return new Promise(function(res,rej){
      var r=indexedDB.open('catalogo-zero');
      r.onerror=function(){rej(r.error);};
      r.onsuccess=function(){
        var d=r.result, out={versao:d.version,stores:Array.from(d.objectStoreNames)};
        var t=d.transaction(['itens','adapt','lixo'],'readonly'), q={};
        ['itens','adapt','lixo'].forEach(function(n){ var g=t.objectStore(n).getAll(); g.onsuccess=function(){q[n]=g.result;}; });
        t.oncomplete=async function(){
          out.itens=q.itens.length; out.adapt=q.adapt.length; out.lixo=q.lixo.length;
          out.nomes=q.itens.map(function(i){return i.nome;}); out.cods=q.itens.map(function(i){return i.cod;});
          var com=q.itens.filter(function(i){return i.fotos&&i.fotos.length;})[0];
          if(com){ var b=com.fotos[0]; out.fotoBytes=b.size; out.fotoTipo=b.type; try{ var bm=await createImageBitmap(b); out.fotoW=bm.width; out.fotoH=bm.height; }catch(e){ out.fotoErro=String(e); } }
          d.close(); res(out);
        };
      };
    });
  },
  semear: function(n,w,h){
    return new Promise(async function(res,rej){
      var r=indexedDB.open('catalogo-zero');
      r.onsuccess=async function(){
        var d=r.result, base=[];
        for(var s=1;s<=5;s++){
          var c=document.createElement('canvas'); c.width=w; c.height=h; var x=c.getContext('2d');
          var g=x.createLinearGradient(0,0,w,h); g.addColorStop(0,'hsl('+(s*50)+',60%,30%)'); g.addColorStop(1,'hsl('+(s*80)+',70%,55%)');
          x.fillStyle=g; x.fillRect(0,0,w,h);
          for(var i=0;i<300;i++){ x.fillStyle='hsl('+((s*13+i*29)%360)+',50%,'+(20+i%50)+'%)'; x.fillRect((i*131+s*17)%w,(i*71+s*29)%h,w/20,h/22); }
          base.push(await new Promise(function(rr){c.toBlob(rr,'image/jpeg',0.8);}));
        }
        var t=d.transaction(['itens','meta'],'readwrite'), s=t.objectStore('itens');
        for(var i=0;i<n;i++){
          s.put({id:'seed-'+i,cod:'PM-'+String(1000+i).padStart(4,'0'),tipo:'Placa principal',estado:'func',nome:'Placa de teste '+i,qtd:1,
                 fotos:[base[i%5],base[(i+1)%5]],criado:Date.now(),atualizado:Date.now()});
        }
        t.objectStore('meta').put({k:'sujo',v:Date.now()});
        t.oncomplete=function(){ d.close(); res(base.map(function(b){return b.size;})); };
        t.onerror=function(){ rej(t.error); };
      };
    });
  }
};
"""

def iniciar(depurar=True):
    sh(f"adb shell am force-stop {PKG}")
    time.sleep(1)
    extra = "--ez depurar true" if depurar else ""
    sh(f"adb shell am start -n {PKG}/.MainActivity {extra}", check=True)

def abrir():
    iniciar()
    p = Pagina(); p.conectar()
    return p

def print_tela(nome):
    sh(f"adb exec-out screencap -p > {OUT}/{nome}.png")

def ls_downloads():
    s = sh("adb shell ls /sdcard/Download/CatalogoZero/")
    return sorted([l.strip() for l in s.split("\n") if l.strip().startswith("CatalogoZero-") and l.strip().endswith(".json")])

def main():
    sh("adb wait-for-device")
    log(sh("adb shell getprop ro.build.version.sdk").strip(), "= SDK do emulador")
    sh("adb shell rm -rf /sdcard/Download/CatalogoZero")
    sh(f"adb uninstall {PKG}")
    log(sh(f"adb install -r {APK}", check=True).strip())

    # ---- 1. abre e funciona ----
    p = abrir()
    ok("abre no emulador e a página carrega", p.ev("!!document.querySelector('#fab')"))
    ok("a ponte do Android existe (backup automático disponível)", p.ev("!!(window.AndroidBridge&&window.AndroidBridge.beginAuto&&window.AndroidBridge.endAuto)"))
    info = p.ev("T.idb()")
    ok("banco de dados na versão 2 com lixeira e meta", info["versao"] == 2 and "lixo" in info["stores"] and "meta" in info["stores"], info["stores"])
    print_tela("01-vazio")

    # ---- 2. cadastro com foto grande ----
    feito = p.ev("T.novoItem('Main TV 43 teste',{codigo:'BN94-16420A',obs:'observacao importante',foto:1})")
    ok("cadastro com foto de 3000x2000 salva", feito is True)
    p.ev("T.sleep(300)")
    info = p.ev("T.idb()")
    ok("item gravado com código PM-0001", info["itens"] == 1 and info["cods"] == ["PM-0001"], info["cods"])
    ok("foto foi reduzida (máx. 1400 px e abaixo de 1,5 MB)", info.get("fotoW", 99999) <= 1400 and info.get("fotoBytes", 1e9) < 1.5e6 and info.get("fotoTipo") == "image/jpeg", f'{info.get("fotoW")}x{info.get("fotoH")} {info.get("fotoBytes")} bytes')
    print_tela("02-com-item")

    # ---- 3. fechar o app de verdade e abrir de novo ----
    p.fechar()
    p = abrir()
    p.esperar("document.querySelectorAll('.card').length>=1", 30)
    info = p.ev("T.idb()")
    ok("depois de fechar o app à força o item continua lá", info["itens"] == 1 and info["nomes"] == ["Main TV 43 teste"])
    ok("a foto continua legível depois de reabrir", info.get("fotoW", 0) > 0, f'{info.get("fotoW")}x{info.get("fotoH")}')
    ok("a foto aparece no cartão", p.ev("(function(){var i=document.querySelector('.card img');return !!i&&i.complete&&i.naturalWidth>0;})()"))

    # ---- 4. cópia automática em Downloads ----
    t0 = time.time()
    while time.time() - t0 < 60 and not ls_downloads():
        time.sleep(2)
    arqs = ls_downloads()
    ok("cópia automática apareceu em Downloads/CatalogoZero sozinha", len(arqs) == 1, arqs)
    if arqs:
        sh(f"adb pull /sdcard/Download/CatalogoZero/{arqs[-1]} {OUT}/copia1.json")
        linhas = [json.loads(l) for l in open(f"{OUT}/copia1.json", encoding="utf-8").read().split("\n") if l.strip()]
        cab, fim = linhas[0], linhas[-1]
        its = [l["o"] for l in linhas if l.get("s") == "i"]
        foto_ok = False
        if its and its[0].get("fotos"):
            raw = base64.b64decode(its[0]["fotos"][0].split(",", 1)[1])
            foto_ok = raw[:3] == b"\xff\xd8\xff" and len(raw) > 5000
        ok("a cópia tem cabeçalho, o item, a foto (JPEG válido) e o marcador de fim", cab.get("app") == "catalogo-zero" and len(its) == 1 and foto_ok and fim.get("fim") is True)
    p.ev("T.sleep(500)")
    estado = p.ev("(function(){document.querySelector('#btnBackup').click();return T.sleep(400).then(function(){return document.querySelector('#bEstado').innerText;});})()")
    ok("a tela de backup mostra a última cópia automática", "Última cópia automática" in (estado or ""), estado)
    print_tela("03-backup")
    p.ev("document.querySelector('#bBack').click()")

    # ---- 5. rascunho sobrevive ao app ser morto (acontece ao abrir a câmera) ----
    p.ev("(async function(){T.click('#fab');await T.sleep(250);T.setv('#fNome','Placa LG que eu estava cadastrando');T.setv('#fObs','nao salvei ainda');await T.foto(1600,1200,2);await T.sleep(1200);})()")
    p.fechar()
    p = abrir()
    p.esperar("!!document.querySelector('[data-act=draftGo]')", 30)
    ok("depois do app ser morto aparece o aviso de rascunho", p.ev("!!document.querySelector('[data-act=draftGo]')"))
    p.ev("T.click('[data-act=draftGo]')")
    p.esperar("!!document.querySelector('#fNome')", 10)
    ok("o rascunho volta com texto e foto", p.ev("document.querySelector('#fNome').value")=="Placa LG que eu estava cadastrando" and p.ev("document.querySelectorAll('#fFotos .foto').length")==1)
    print_tela("04-rascunho")
    p.ev("document.querySelector('#fSave').click()")
    p.esperar("document.querySelector('#sheetForm').hidden", 10)
    info = p.ev("T.idb()")
    ok("salvar o rascunho cria o segundo item PM-0002", info["itens"] == 2 and "PM-0002" in info["cods"], info["cods"])

    # ---- 6. botão voltar do Android ----
    p.ev("document.querySelector('.card').click()")
    p.esperar("!document.querySelector('#sheetDetail').hidden", 5)
    sh("adb shell input keyevent KEYCODE_BACK")
    time.sleep(1.2)
    ok("botão voltar do Android fecha a tela do item sem sair do app", p.ev("document.querySelector('#sheetDetail').hidden") and sh(f"adb shell pidof {PKG}").strip() != "")

    # ---- 7. seletor de foto / câmera do Android abre e cancela sem travar ----
    p.ev("T.click('#fab')")
    p.esperar("!!document.querySelector('#fAdd')", 5)
    box = p.ev("(function(){var r=document.querySelector('#fAdd').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2];})()")
    for tipo in ("mousePressed", "mouseReleased"):
        p.cdp("Input.dispatchMouseEvent", {"type": tipo, "x": box[0], "y": box[1], "button": "left", "clickCount": 1})
    time.sleep(3.5)
    foco = sh("adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -3")
    log("foco depois de tocar em Foto:", foco.strip())
    print_tela("05-seletor-de-foto")
    ok("tocar em Foto abre o seletor do Android (galeria e câmera)", PKG not in foco.split("mCurrentFocus")[1] if "mCurrentFocus" in foco else False, foco.strip()[:200])
    sh("adb shell input keyevent KEYCODE_BACK")
    time.sleep(1.5)
    foco2 = sh("adb shell dumpsys window | grep mCurrentFocus")
    ok("cancelar o seletor volta para o app sem travar", PKG in foco2, foco2.strip()[:160])
    ok("a página continua respondendo depois de cancelar", p.ev("T.sleep(100).then(function(){return !!document.querySelector('#fNome');})"))
    p.ev("T.click('#fCancel')")

    # ---- 8. atualizar o app por cima (mesma assinatura) mantém os dados ----
    p.fechar()
    sh(f"adb shell am force-stop {PKG}")
    log(sh(f"adb install -r {APK}", check=True).strip())
    p = abrir()
    p.esperar("document.querySelectorAll('.card').length>=2", 30)
    info = p.ev("T.idb()")
    ok("instalar a versão por cima não apaga os dados", info["itens"] == 2, info["nomes"])

    # ---- 9. lixeira no aparelho ----
    p.ev("document.querySelector('.card').click()")
    p.esperar("!document.querySelector('#sheetDetail').hidden", 5)
    p.ev("(async function(){T.click('#dDel');await T.sleep(200);T.click('#dDel');await T.sleep(600);})()")
    info = p.ev("T.idb()")
    ok("excluir manda para a lixeira (1 item + 1 na lixeira)", info["itens"] == 1 and info["lixo"] == 1, f'{info["itens"]} itens, {info["lixo"]} lixeira')
    p.ev("(async function(){T.click('#btnBackup');await T.sleep(300);T.click('#bLixo');await T.sleep(300);T.click('[data-rec]');await T.sleep(700);T.click('#bBack');await T.sleep(200);T.click('#bBack');})()")
    info = p.ev("T.idb()")
    ok("recuperar da lixeira devolve o item", info["itens"] == 2 and info["lixo"] == 0)

    # ---- 10. muito volume: 150 placas com 2 fotos cada ----
    tams = p.ev("T.semear(150,1400,1050)")
    log("tamanhos das fotos de teste (bytes):", tams)
    p.fechar(); p = abrir()
    p.esperar("document.querySelectorAll('.card').length>=10", 40)
    info = p.ev("T.idb()")
    ok("150 placas com 300 fotos carregam", info["itens"] == 152, info["itens"])
    t1 = time.time()
    p.ev("(async function(){T.click('#btnBackup');await T.sleep(400);T.click('#bAuto');})()")
    # espera a cópia nova (a de teste mais nova tem de ser maior que a primeira)
    grande = None
    while time.time() - t1 < 200:
        time.sleep(3)
        a = ls_downloads()
        if a:
            tam = sh(f"adb shell stat -c %s /sdcard/Download/CatalogoZero/{a[-1]}").strip()
            if tam.isdigit() and int(tam) > 5_000_000:
                grande = (a[-1], int(tam)); break
    seg = time.time() - t1
    ok("cópia automática de 150 placas termina e grava arquivo grande", grande is not None, f"{grande} em {seg:.0f}s")
    ok("o app continua vivo depois da cópia grande", sh(f"adb shell pidof {PKG}").strip() != "" and p.ev("1+1") == 2)
    print_tela("06-backup-grande")
    ok("sobra no máximo 1 cópia por dia (as do mesmo dia são trocadas)", len(ls_downloads()) == 1, ls_downloads())
    if grande:
        sh(f"adb pull /sdcard/Download/CatalogoZero/{grande[0]} {OUT}/copia_grande.json", timeout=300)

    # ---- 11. desinstalar apaga o app, mas a cópia fica; restaurar traz tudo ----
    p.fechar()
    sh(f"adb uninstall {PKG}", check=True)
    ok("a cópia continua em Downloads depois de desinstalar o app", len(ls_downloads()) == 1, ls_downloads())
    log(sh(f"adb install -r {APK}", check=True).strip())
    p = abrir()
    p.esperar("!!document.querySelector('.empty')", 20)
    info = p.ev("T.idb()")
    ok("app reinstalado começa vazio (como acontece num celular novo)", info["itens"] == 0)
    if os.path.exists(f"{OUT}/copia_grande.json"):
        dados = open(f"{OUT}/copia_grande.json", encoding="utf-8").read()
        # entrega o arquivo para a tela de restaurar (mesmo caminho do botão Escolher arquivo)
        p.ev("(async function(){T.click('[data-act=backup]');await T.sleep(400);})()")
        # envia em pedaços para nao estourar o limite do DevTools
        p.ev("window.__buf=[]")
        passo = 600000
        for i in range(0, len(dados), passo):
            p.ev("window.__buf.push(" + json.dumps(dados[i:i+passo]) + ")")
        p.ev("""(function(){
          var f=new File(window.__buf,'copia.json',{type:'application/json'});
          var dt=new DataTransfer(); dt.items.add(f);
          var inp=document.querySelector('#bFile'); inp.files=dt.files; inp.dispatchEvent(new Event('change',{bubbles:true}));
        })()""")
        p.esperar("document.querySelector('#bMsg').textContent.indexOf('restaurados')>=0", 180)
        msg = p.ev("document.querySelector('#bMsg').textContent")
        info = p.ev("T.idb()")
        ok("restaurar a cópia num app reinstalado traz as 152 placas de volta", info["itens"] == 152, f"{info['itens']} itens · {msg}")
        ok("as fotos restauradas abrem", info.get("fotoW", 0) > 0, f'{info.get("fotoW")}x{info.get("fotoH")}')
        print_tela("07-restaurado")
    p.fechar()

    # ---- resumo ----
    falhas = [r for r in resultados if not r[1]]
    log(f"\n{len(resultados)-len(falhas)}/{len(resultados)} verificações passaram")
    sh("adb logcat -d -t 400 > " + OUT + "/logcat.txt")
    return 1 if falhas else 0

if __name__ == "__main__":
    try:
        codigo = main()
    except Exception:
        log("ERRO NO TESTE:\n" + traceback.format_exc())
        try:
            sh("adb logcat -d -t 600 > " + OUT + "/logcat.txt")
            sh(f"adb exec-out screencap -p > {OUT}/erro.png")
        except Exception:
            pass
        codigo = 2
    sys.exit(codigo)
