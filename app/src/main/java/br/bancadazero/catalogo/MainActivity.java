package br.bancadazero.catalogo;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
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
