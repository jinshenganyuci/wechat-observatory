package cc.wechat.observatory;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Installation instructions only. Configuration is edited inside WeChat. */
public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("WeChat Observatory");
        setContentView(createContentView());
    }

    private View createContentView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(content);

        addText(content, "WeChat Observatory", 24);
        addText(content, "配置请在微信内完成。此应用只需安装并在 LSPosed 中启用，无需保持后台。", 16);
        addText(content, "使用方法", 19);
        addText(content, "1. 在 LSPosed 中启用本模块，勾选微信，然后强行停止并重新打开微信。\n"
                + "2. 进入微信 → 我 → 设置 → Observatory 网关。\n"
                + "3. 填写服务地址和 API Key，开启“启用网关”后保存，即刻生效。", 16);

        Button open = new Button(this);
        open.setText("打开微信");
        open.setAllCaps(false);
        open.setOnClickListener(view -> openWeChat(this));
        content.addView(open, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        addText(content, "停用或卸载", 19);
        addText(content, "卸载前，请在微信网关设置中点击“停用并删除配置”。"
                + "仅卸载本模块不会自动删除微信中保存的配置。", 16);
        addText(content, "从 0.1.17 升级", 19);
        addText(content, "请在微信网关设置中重新填写配置，本版不自动导入旧配置。"
                + "旧版公共文件需手动删除：Documents/WeChatObservatory 文件夹。", 16);
        return scroll;
    }

    static void openWeChat(Context context) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"));
            }
            context.startActivity(launch);
        } catch (RuntimeException unavailable) {
            Toast.makeText(context, "无法打开微信，请从桌面打开微信后进入设置。", Toast.LENGTH_LONG).show();
        }
    }

    private void addText(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, 0, 0, dp(16));
        parent.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
