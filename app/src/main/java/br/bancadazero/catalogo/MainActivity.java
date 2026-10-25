package br.bancadazero.catalogo;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Catalogo Zero (Bancada Zero).
 * Casca Android que abre o catalogo (pagina em assets/index.html) e liga
 * foto/camera, backup (compartilhar arquivo) e o botao voltar.
 * Os dados ficam guardados no proprio celular (IndexedDB do WebView).
 */
public class MainActivity extends Activity {

    private static final int REQ_ARQUIVO = 1001;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> retornoArquivo;
    private Uri cameraUri;

    private FileOutputStream saidaBackup;
    private File arquivoBackup;
    private String nomeAuto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getBooleanExtra("depurar", false)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        limparCache();
        if (Build.VERSION.SDK_INT < 29
                && androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }
        getWindow().setStatusBarColor(Color.parseColor("#09090A"));
        getWindow().setNavigationBarColor(Color.parseColor("#09090A"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#09090A"));
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // o processo da pagina morreu (pouca memoria): reabre o app em vez de fechar de vez
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ViewGroup pai = (ViewGroup) web.getParent();
                            if (pai != null) pai.removeView(web);
                            web.destroy();
                        } catch (Exception ignorado) {
                        }
                        recreate();
                    }
                });
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                return host == null || !host.equals("appassets.androidplatform.net");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (retornoArquivo != null) {
                    retornoArquivo.onReceiveValue(null);
                }
                retornoArquivo = callback;
                cameraUri = null;

                boolean imagem = false;
                String[] tipos = params.getAcceptTypes();
                if (tipos != null && tipos.length > 0 && tipos[0] != null && tipos[0].startsWith("image")) {
                    imagem = true;
                }

                Intent escolher = new Intent(Intent.ACTION_GET_CONTENT);
                escolher.addCategory(Intent.CATEGORY_OPENABLE);
                escolher.setType(imagem ? "image/*" : "*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    escolher.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                Intent seletor = Intent.createChooser(escolher, imagem ? "Foto" : "Arquivo");

                if (imagem) {
                    try {
                        File pasta = new File(getCacheDir(), "fotos");
                        pasta.mkdirs();
                        File foto = new File(pasta, "cam_" + System.currentTimeMillis() + ".jpg");
                        cameraUri = FileProvider.getUriForFile(MainActivity.this,
                                getPackageName() + ".fileprovider", foto);
                        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        camera.setClipData(ClipData.newRawUri("foto", cameraUri));
                        camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        seletor.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                    } catch (Exception e) {
                        cameraUri = null;
                    }
                }

                try {
                    startActivityForResult(seletor, REQ_ARQUIVO);
                } catch (Exception e) {
                    retornoArquivo = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Ponte(), "AndroidBridge");

        web.loadUrl(HOME);
    }

    /** Apaga fotos da camera e arquivos temporarios antigos do cache. */
    private void limparCache() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long limite = System.currentTimeMillis() - 2L * 24 * 3600 * 1000;
                    String[] pastas = {"fotos", "backup"};
                    for (String nome : pastas) {
                        File[] lista = new File(getCacheDir(), nome).listFiles();
                        if (lista == null) continue;
                        for (File f : lista) {
                            if (f.lastModified() < limite) f.delete();
                        }
                    }
                } catch (Exception ignorado) {
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_ARQUIVO || retornoArquivo == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] resultado = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                resultado = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    resultado[i] = clip.getItemAt(i).getUri();
                }
            } else if (data != null && data.getData() != null) {
                resultado = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                resultado = new Uri[]{cameraUri};
            }
        }
        retornoArquivo.onReceiveValue(resultado);
        retornoArquivo = null;
        cameraUri = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){try{return window.__voltar&&window.__voltar()===true;}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String valor) {
                        if (!"true".equals(valor)) {
                            finish();
                        }
                    }
                });
    }

    private void aviso(final String texto) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, texto, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void fecharBackup() {
        try {
            if (saidaBackup != null) {
                saidaBackup.close();
            }
        } catch (Exception ignorado) {
        }
        saidaBackup = null;
    }

    private void responderAuto(final boolean ok, final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript("window.__autoDone&&window.__autoDone(" + ok + "," + JSONObject.quote(msg) + ")", null);
                }
            }
        });
    }

    /** Copia o backup para Downloads/CatalogoZero (continua la mesmo se o app for desinstalado). */
    private String copiarParaDownloads(File origem, String nome) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CatalogoZero");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("sem acesso a Downloads");
            try {
                OutputStream out = cr.openOutputStream(uri);
                InputStream in = new FileInputStream(origem);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                } finally {
                    in.close();
                    out.close();
                }
                ContentValues pronto = new ContentValues();
                pronto.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, pronto, null, null);
                long gravado = -1;
                Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) gravado = c.getLong(0);
                    } finally {
                        c.close();
                    }
                }
                if (gravado != origem.length()) throw new Exception("arquivo gravado incompleto");
            } catch (Exception e) {
                try {
                    cr.delete(uri, null, null);
                } catch (Exception ignorado) {
                }
                throw e;
            }
            podarDownloads(cr);
            return "Downloads/CatalogoZero/" + nome;
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw new Exception("sem permissão para gravar em Downloads");
        }
        File pasta = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CatalogoZero");
        if (!pasta.exists() && !pasta.mkdirs()) throw new Exception("não consegui criar a pasta");
        File destino = new File(pasta, nome);
        InputStream in = new FileInputStream(origem);
        FileOutputStream out = new FileOutputStream(destino, false);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            in.close();
            out.close();
        }
        if (destino.length() != origem.length()) {
            destino.delete();
            throw new Exception("arquivo gravado incompleto");
        }
        File[] todos = pasta.listFiles();
        if (todos != null) {
            java.util.Arrays.sort(todos, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return b.getName().compareTo(a.getName());
                }
            });
            HashSet<String> datas = new HashSet<String>();
            for (File f : todos) {
                String nm = f.getName();
                if (!nm.startsWith("CatalogoZero-") || nm.length() < 23) continue;
                String data = nm.substring(13, 23);
                if (datas.contains(data) || datas.size() >= 10) {
                    f.delete();
                } else {
                    datas.add(data);
                }
            }
        }
        return "Downloads/CatalogoZero/" + nome;
    }

    /** Guarda 1 copia por dia, das 10 datas mais recentes. So mexe nos arquivos que o proprio app criou. */
    private void podarDownloads(ContentResolver cr) {
        try {
            Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{Environment.DIRECTORY_DOWNLOADS + "/CatalogoZero%", "CatalogoZero-%.json"},
                    MediaStore.MediaColumns.DISPLAY_NAME + " DESC");
            if (c == null) return;
            HashSet<String> datas = new HashSet<String>();
            java.util.ArrayList<Long> apagar = new java.util.ArrayList<Long>();
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String nm = c.getString(1);
                    if (nm == null || nm.length() < 23) continue;
                    String data = nm.substring(13, 23);
                    if (datas.contains(data) || datas.size() >= 10) {
                        apagar.add(id);
                    } else {
                        datas.add(data);
                    }
                }
            } finally {
                c.close();
            }
            for (Long id : apagar) {
                try {
                    cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null);
                } catch (Exception ignorado) {
                }
            }
        } catch (Exception ignorado) {
        }
    }

    /** Ponte chamada pelo JavaScript da pagina (window.AndroidBridge). */
    private class Ponte {

        @JavascriptInterface
        public void beginSave(String nome) {
            try {
                fecharBackup();
                File pasta = new File(getCacheDir(), "backup");
                pasta.mkdirs();
                String seguro = nome.replaceAll("[^A-Za-z0-9._-]", "_");
                arquivoBackup = new File(pasta, seguro);
                saidaBackup = new FileOutputStream(arquivoBackup, false);
            } catch (Exception e) {
                saidaBackup = null;
                aviso("Não consegui preparar o backup.");
            }
        }

        @JavascriptInterface
        public void beginAuto(String nome) {
            try {
                fecharBackup();
                File pasta = new File(getCacheDir(), "backup");
                pasta.mkdirs();
                nomeAuto = nome.replaceAll("[^A-Za-z0-9._-]", "_");
                arquivoBackup = new File(pasta, "auto.tmp");
                saidaBackup = new FileOutputStream(arquivoBackup, false);
            } catch (Exception e) {
                saidaBackup = null;
                responderAuto(false, "não consegui preparar o arquivo");
            }
        }

        @JavascriptInterface
        public void endAuto() {
            final File origem = arquivoBackup;
            final String nome = nomeAuto;
            try {
                if (saidaBackup == null || origem == null) {
                    responderAuto(false, "arquivo não preparado");
                    return;
                }
                saidaBackup.close();
                saidaBackup = null;
            } catch (Exception e) {
                responderAuto(false, "não consegui fechar o arquivo");
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String onde = copiarParaDownloads(origem, nome);
                        origem.delete();
                        responderAuto(true, onde);
                    } catch (Exception e) {
                        responderAuto(false, String.valueOf(e.getMessage()));
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void abortSave() {
            fecharBackup();
            if (arquivoBackup != null) arquivoBackup.delete();
        }

        @JavascriptInterface
        public void chunk(String texto) {
            try {
                if (saidaBackup != null) {
                    saidaBackup.write(texto.getBytes("UTF-8"));
                }
            } catch (Exception e) {
                fecharBackup();
                aviso("Não consegui gravar o backup.");
            }
        }

        @JavascriptInterface
        public void endSave() {
            try {
                if (saidaBackup == null || arquivoBackup == null) {
                    aviso("Não consegui gerar o backup.");
                    return;
                }
                saidaBackup.close();
                saidaBackup = null;
                final Uri uri = FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".fileprovider", arquivoBackup);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent enviar = new Intent(Intent.ACTION_SEND);
                        enviar.setType("application/json");
                        enviar.putExtra(Intent.EXTRA_STREAM, uri);
                        enviar.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(enviar, "Guardar backup"));
                    }
                });
            } catch (Exception e) {
                aviso("Não consegui compartilhar o backup.");
            }
        }
    }
}
