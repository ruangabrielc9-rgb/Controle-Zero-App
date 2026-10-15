package br.bancadazero.controle;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Bancada Zero - Controle de servico (IR).
 * Tela inicial para escolher a marca e tela de controle com abas.
 */
public class MainActivity extends Activity {

    // ===== Cores do tema =====
    static final int BG = 0xFF0D0D0F;
    static final int RED = 0xFFE10600;
    static final int RED_DARK = 0xFF7A0300;
    static final int GLASS = 0x14FFFFFF;
    static final int GLASS_BORDER = 0x22FFFFFF;
    static final int MUTED = 0xFF9B9BA3;
    static final int GREEN = 0xFF2BFF88;
    static final int AMBER = 0xFFFFB347;

    enum Proto { SAMSUNG, NEC }

    /** Uma tecla: nome, codigo hexadecimal de 32 bits e protocolo. */
    static class Key {
        final String label;
        final String hex;
        final Proto proto;
        Key(String label, String hex, Proto proto) {
            this.label = label;
            this.hex = hex;
            this.proto = proto;
        }
    }

    // ===== Codigos Samsung (Samsung32, 38 kHz) =====
    static Key sam(String l, String h) { return new Key(l, h, Proto.SAMSUNG); }
    static final Key S_POWER = sam("POWER", "E0E040BF");
    static final Key S_MUTE = sam("MUTE", "E0E0F00F");
    static final Key S_INFO = sam("INFO", "E0E0F807");
    static final Key S_FACTORY = sam("FACTORY", "E0E0DC23");
    static final Key S_MENU = sam("MENU", "E0E058A7");
    static final Key S_SOURCE = sam("SOURCE", "E0E0807F");
    static final Key S_UP = sam("CIMA", "E0E006F9");
    static final Key S_DOWN = sam("BAIXO", "E0E08679");
    static final Key S_LEFT = sam("ESQUERDA", "E0E0A659");
    static final Key S_RIGHT = sam("DIREITA", "E0E046B9");
    static final Key S_OK = sam("OK", "E0E016E9");
    static final Key S_RETURN = sam("RETURN", "E0E01AE5");
    static final Key S_EXIT = sam("EXIT", "E0E0B44B");
    static final Key[] S_NUM = {
            sam("0", "E0E08877"), sam("1", "E0E020DF"), sam("2", "E0E0A05F"),
            sam("3", "E0E0609F"), sam("4", "E0E010EF"), sam("5", "E0E0906F"),
            sam("6", "E0E050AF"), sam("7", "E0E030CF"), sam("8", "E0E0B04F"),
            sam("9", "E0E0708F")
    };

    // ===== Codigos LG (NEC, 38 kHz) =====
    static Key lg(String l, String h) { return new Key(l, h, Proto.NEC); }
    static final Key L_POWER = lg("POWER", "20DF10EF");
    static final Key L_MUTE = lg("MUTE", "20DF906F");
    static final Key L_INSTART = lg("IN-START", "20DFDF20");
    static final Key L_EZADJUST = lg("EZ-ADJUST", "20DFFF00");
    static final Key L_MENU = lg("MENU", "20DFC23D");
    static final Key L_UP = lg("CIMA", "20DF02FD");
    static final Key L_DOWN = lg("BAIXO", "20DF827D");
    static final Key L_LEFT = lg("ESQUERDA", "20DFE01F");
    static final Key L_RIGHT = lg("DIREITA", "20DF609F");
    static final Key L_OK = lg("OK", "20DF22DD");
    static final Key L_BACK = lg("VOLTAR", "20DF14EB");
    static final Key[] L_NUM = {
            lg("0", "20DF08F7"), lg("1", "20DF8877"), lg("2", "20DF48B7"),
            lg("3", "20DFC837"), lg("4", "20DF28D7"), lg("5", "20DFA857"),
            lg("6", "20DF6897"), lg("7", "20DFE817"), lg("8", "20DF18E7"),
            lg("9", "20DF9867")
    };

    // ===== Estado =====
    private FrameLayout stage;
    private View homeView;
    private View remoteView;
    private TextView statusText;
    private ConsumerIrManager irManager;
    private boolean irAvailable = false;
    private boolean warnedNoIr = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private int rowH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        try {
            irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
            irAvailable = irManager != null && irManager.hasIrEmitter();
        } catch (Exception e) {
            irAvailable = false;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        View glow = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A0805, 0x000D0D0F});
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        homeView = buildHome();
        stage.addView(homeView);
        animateHomeIn();
    }

    // =========================================================
    // Tela inicial
    // =========================================================
    private View buildHome() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(44), dp(22), dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(logoRow("BANCADA ZERO", 14));

        TextView title = text("Qual TV você vai atender?", 32, Color.WHITE, true);
        title.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams tlp = lp(-1, -2);
        tlp.topMargin = dp(30);
        col.addView(title, tlp);

        TextView sub = text("Toque na marca para abrir o controle de serviço.", 15, MUTED, false);
        LinearLayout.LayoutParams slp = lp(-1, -2);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(24);
        col.addView(sub, slp);

        col.addView(brandCardRed("Samsung", "Info + Factory · menu de serviço", "Disponível"), cardLp());
        col.addView(brandCardGlass("LG", "In-Start · Ez-Adjust", "Disponível", true), cardLp());
        col.addView(brandCardGlass("Sony", "Códigos de serviço", "Em breve", false), cardLp());
        col.addView(brandCardGlass("TCL · Philco", "Códigos de serviço", "Em breve", false), cardLp());

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView foot = text("Desenvolvido por Ruan\n⭕  Projeto Bancada Zero · v1.5", 13, MUTED, false);
        foot.setLineSpacing(0, 1.25f);
        foot.setGravity(Gravity.CENTER);
        foot.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams flp = lp(-1, -2);
        flp.topMargin = dp(18);
        col.addView(foot, flp);

        return sv;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams l = lp(-1, dp(104));
        l.bottomMargin = dp(14);
        return l;
    }

    private View brandCardRed(final String name, String desc, String tag) {
        LinearLayout card = baseCard(name, desc, tag, true);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{RED, RED_DARK});
        d.setCornerRadius(dp(26));
        card.setBackground(d);
        attachPress(card);
        card.setOnClickListener(v -> openBrand(name));
        return card;
    }

    private View brandCardGlass(final String name, String desc, String tag, boolean enabled) {
        LinearLayout card = baseCard(name, desc, tag, false);
        card.setBackground(glassShape(26));
        if (enabled) {
            attachPress(card);
            card.setOnClickListener(v -> openBrand(name));
        } else {
            card.setAlpha(0.5f);
        }
        return card;
    }

    private LinearLayout baseCard(String name, String desc, String tag, boolean red) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(16), dp(22), dp(16));
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(name, 26, Color.WHITE, true);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = text(tag, 13, red ? 0xFFFFD9D6 : MUTED, false);
        top.addView(t);
        card.addView(top);

        TextView d = text(desc, 14, red ? 0xFFFFD9D6 : MUTED, false);
        LinearLayout.LayoutParams dl = lp(-1, -2);
        dl.topMargin = dp(6);
        card.addView(d, dl);
        return card;
    }

    private void animateHomeIn() {
        if (!(homeView instanceof ScrollView)) return;
        ViewGroup col = (ViewGroup) ((ScrollView) homeView).getChildAt(0);
        for (int i = 0; i < col.getChildCount(); i++) {
            View c = col.getChildAt(i);
            float target = c.getAlpha();
            c.setAlpha(0f);
            c.setTranslationY(dp(22));
            c.animate().alpha(target).translationY(0).setStartDelay(60L * i).setDuration(320).start();
        }
    }

    // =========================================================
    // Tela do controle
    // =========================================================
    private void openBrand(String name) {
        if (remoteView != null) return;
        final boolean isSam = name.equals("Samsung");
        remoteView = buildRemote(name, isSam);
        stage.addView(remoteView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final int w = stage.getWidth() > 0 ? stage.getWidth() : dp(360);
        remoteView.setTranslationX(w);
        remoteView.animate().translationX(0).setDuration(280).start();
        homeView.animate().translationX(-w / 5f).alpha(0.4f).setDuration(280).start();

        if (!irAvailable && !warnedNoIr) {
            warnedNoIr = true;
            handler.postDelayed(this::showNoIrDialog, 350);
        }
    }

    private void closeRemote() {
        if (remoteView == null) return;
        final View rv = remoteView;
        remoteView = null;
        final int w = stage.getWidth() > 0 ? stage.getWidth() : dp(360);
        rv.animate().translationX(w).setDuration(250).withEndAction(() -> stage.removeView(rv)).start();
        homeView.animate().translationX(0).alpha(1f).setDuration(250).start();
    }

    @Override
    public void onBackPressed() {
        if (remoteView != null) {
            closeRemote();
        } else {
            super.onBackPressed();
        }
    }

    private View buildRemote(String name, final boolean isSam) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(BG);
        wrap.setClickable(true);

        View glow = new View(this);
        glow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A0805, 0x000D0D0F}));
        wrap.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        wrap.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(32), dp(18), dp(16));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Cabecalho
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 40, Color.WHITE, false);
        back.setPadding(dp(6), 0, dp(16), dp(4));
        attachPress(back);
        back.setOnClickListener(v -> closeRemote());
        head.addView(back);
        head.addView(logoRow(name.toUpperCase(), 13));
        col.addView(head);

        // Status do emissor IR
        statusText = text(statusIdle(), 14, irAvailable ? GREEN : AMBER, false);
        statusText.setPadding(dp(16), dp(10), dp(16), dp(10));
        statusText.setBackground(glassShape(20));
        LinearLayout.LayoutParams stl = lp(-1, -2);
        stl.topMargin = dp(12);
        col.addView(statusText, stl);

        // Altura de cada linha: preenche a tela (9 linhas de botoes)
        int totalH = stage.getHeight() > 0 ? stage.getHeight() : dp(720);
        int rows = isSam ? 10 : 9;
        int h = (totalH - (isSam ? dp(290) : dp(250))) / rows - dp(10);
        rowH = Math.max(dp(46), Math.min(dp(70), h));

        LinearLayout grid = isSam ? samsungGrid() : lgGrid();
        LinearLayout.LayoutParams gl = lp(-1, -2);
        gl.topMargin = dp(16);
        col.addView(grid, gl);

        String note = isSam
                ? "Códigos 0000 · 0028 · 0038 · 0098: use dentro do menu de serviço, no item Advanced. A sequência Mute·1·8·2·Power varia por modelo; teste antes de usar com cliente."
                : "LG: In-Start e Ez-Adjust de tabela pública; navegação e números ainda a testar em TV real.";
        TextView nt = text(note, 11.5f, AMBER, false);
        nt.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams nl = lp(-1, -2);
        nl.topMargin = dp(4);
        col.addView(nt, nl);

        return wrap;
    }

    // ----- Grade unica Samsung -----
    private LinearLayout samsungGrid() {
        LinearLayout c = vbox();
        c.addView(row(btn("POWER", 1, S_POWER), btn("INFO", 0, S_INFO), btn("FACTORY", 2, S_FACTORY)));
        LinearLayout r2 = row(btn("MUTE", 0, S_MUTE), seqBtn(new Key[]{
                S_MUTE, S_NUM[1], S_NUM[8], S_NUM[2], S_POWER}));
        ((LinearLayout.LayoutParams) r2.getChildAt(1).getLayoutParams()).weight = 2f;
        c.addView(r2);
        c.addView(row(codeBtn("0000"), codeBtn("0028"), codeBtn("0038"), codeBtn("0098")));
        addNumbers(c, S_NUM, S_RETURN, S_OK);
        c.addView(row(btn("RETURN", 0, S_RETURN), btn("▲", 0, S_UP), btn("EXIT", 0, S_EXIT)));
        c.addView(row(btn("◀", 0, S_LEFT), btn("OK", 1, S_OK), btn("▶", 0, S_RIGHT)));
        c.addView(row(btn("MENU", 0, S_MENU), btn("▼", 0, S_DOWN), btn("SOURCE", 0, S_SOURCE)));
        return c;
    }

    // ----- Grade unica LG -----
    private LinearLayout lgGrid() {
        LinearLayout c = vbox();
        TextView ez = btn("EZ-ADJUST", 2, L_EZADJUST);
        SpannableString ezText = new SpannableString("EZ-ADJUST\n(ADJ)");
        ezText.setSpan(new RelativeSizeSpan(0.7f), 10, ezText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ez.setText(ezText);
        c.addView(row(btn("POWER", 1, L_POWER), btn("IN-START", 2, L_INSTART), ez));
        c.addView(row(btn("MUTE", 0, L_MUTE), btn("MENU", 0, L_MENU), btn("VOLTAR", 0, L_BACK)));
        addNumbers(c, L_NUM, L_BACK, L_OK);
        c.addView(row(blank(), btn("▲", 0, L_UP), blank()));
        c.addView(row(btn("◀", 0, L_LEFT), btn("OK", 1, L_OK), btn("▶", 0, L_RIGHT)));
        c.addView(row(blank(), btn("▼", 0, L_DOWN), blank()));
        return c;
    }

    private void addNumbers(LinearLayout c, Key[] n, Key back, Key ok) {
        c.addView(row(btn("1", 0, n[1]), btn("2", 0, n[2]), btn("3", 0, n[3])));
        c.addView(row(btn("4", 0, n[4]), btn("5", 0, n[5]), btn("6", 0, n[6])));
        c.addView(row(btn("7", 0, n[7]), btn("8", 0, n[8]), btn("9", 0, n[9])));
        c.addView(row(btn("←", 0, back), btn("0", 0, n[0]), btn("↵", 1, ok)));
    }

    private View blank() {
        return new View(this);
    }

    // =========================================================
    // Botoes e envio IR
    // =========================================================
    private LinearLayout vbox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rl = lp(-1, rowH);
        rl.bottomMargin = dp(10);
        r.setLayoutParams(rl);
        for (View v : views) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            l.leftMargin = dp(6);
            l.rightMargin = dp(6);
            r.addView(v, l);
        }
        return r;
    }

    /** style: 0 vidro, 1 vermelho, 2 branco. */
    private TextView btn(String label, int style, Key key) {
        int len = label.length();
        TextView t = text(label, len <= 2 ? 24 : (len <= 4 ? 20 : (len <= 6 ? 16 : 14)), Color.WHITE, style != 0);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(33));
        if (style == 0) {
            d.setColor(GLASS);
            d.setStroke(dp(1), GLASS_BORDER);
        } else if (style == 1) {
            d.setColor(RED);
        } else {
            d.setColor(0xFFFFFFFF);
            t.setTextColor(0xFF000000);
        }
        t.setBackground(d);
        bindKey(t, key);
        return t;
    }

    /** Atalho de codigo de 4 digitos: envia um digito de cada vez, em sequencia. */
    private View codeBtn(final String code) {
        TextView t = btn(code, 0, S_NUM[0]);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(33));
        d.setColor(GLASS);
        d.setStroke(dp(1), RED);
        t.setBackground(d);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setOnClickListener(v -> {
            Key[] seq = new Key[code.length()];
            for (int i = 0; i < code.length(); i++) {
                seq[i] = S_NUM[code.charAt(i) - '0'];
            }
            sendSequence(seq);
        });
        return t;
    }

    private View seqBtn(final Key[] seq) {
        TextView t = text("Sequência de serviço\nMute · 1 · 8 · 2 · Power", 12.5f, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glassShape(33));
        attachPress(t);
        t.setOnClickListener(v -> sendSequence(seq));
        return t;
    }

    private void bindKey(View v, final Key key) {
        attachPress(v);
        v.setOnClickListener(x -> sendKey(key));
    }

    /** Modo leve: sem vibracao nem animacao ao tocar, para nao atrapalhar o envio do sinal. */
    private void attachPress(View v) {
        // intencionalmente vazio
    }

    private void sendSequence(final Key[] seq) {
        for (int i = 0; i < seq.length; i++) {
            final Key k = seq[i];
            handler.postDelayed(() -> sendKey(k), 420L * i);
        }
    }

    private void sendKey(Key key) {
        if (!irAvailable) {
            flashStatus("Sem emissor IR neste celular", AMBER);
            if (!warnedNoIr) {
                warnedNoIr = true;
                showNoIrDialog();
            }
            return;
        }
        // Igual ao app antigo: o sinal e enviado em uma thread separada.
        final int[] pat = pattern(key);
        final String msg = "● Enviado: " + key.label + "  ·  " + key.hex;
        new Thread(() -> {
            try {
                irManager.transmit(38000, pat);
                runOnUiThread(() -> flashStatus(msg, GREEN));
            } catch (Exception e) {
                runOnUiThread(() -> flashStatus("Falha ao enviar o sinal", AMBER));
            }
        }).start();
    }

    /** Monta o padrao de pulsos (microssegundos) a partir do codigo de 32 bits. */
    private int[] pattern(Key key) {
        long v = Long.parseLong(key.hex, 16);
        int[] p = new int[67];
        p[0] = key.proto == Proto.SAMSUNG ? 4500 : 9000;
        p[1] = 4500;
        int i = 2;
        for (int b = 31; b >= 0; b--) {
            p[i++] = 560;
            p[i++] = ((v >> b) & 1L) == 1L ? 1690 : 560;
        }
        p[i] = 560;
        return p;
    }

    private String statusIdle() {
        return irAvailable ? "● Emissor IR pronto · 38 kHz" : "● Sem emissor IR neste celular";
    }

    private void flashStatus(String msg, int color) {
        if (statusText == null) return;
        statusText.setText(msg);
        statusText.setTextColor(color);
        handler.removeCallbacks(resetStatus);
        handler.postDelayed(resetStatus, 2200);
    }

    private final Runnable resetStatus = new Runnable() {
        @Override
        public void run() {
            if (statusText != null) {
                statusText.setText(statusIdle());
                statusText.setTextColor(irAvailable ? GREEN : AMBER);
            }
        }
    };

    private void showNoIrDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sem emissor infravermelho")
                .setMessage("Este celular não tem emissor IR. O app abre normalmente, "
                        + "mas os botões não conseguem enviar sinal para a TV. "
                        + "Alguns modelos funcionam com um emissor IR USB-C.")
                .setPositiveButton("Entendi", null)
                .show();
    }

    // =========================================================
    // Utilitarios de interface
    // =========================================================
    private View logoRow(String title, int sp) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        View ring = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(3), RED);
        ring.setBackground(d);
        r.addView(ring, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView t = text(title, sp, 0xFFD6D6DC, false);
        t.setLetterSpacing(0.3f);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.leftMargin = dp(12);
        r.addView(t, l);
        return r;
    }

    private GradientDrawable glassShape(int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(GLASS);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), GLASS_BORDER);
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
