package com.hongshikaikai.aicraft.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/**
 * 文本工具。
 *
 * <p>统一走原版 {@link ChatColor#translateAlternateColorCodes(char, String)} 把 {@code &}
 * 颜色代码翻译成 {@code §}，再用 Adventure 的 legacy 序列化器转成
 * {@link Component}（1.20.5+ 推荐的展示方式，避免使用已废弃的 String API）。</p>
 */
public final class Text {

    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    /**
     * 把 {@code &} 颜色代码翻译为 {@code §} 颜色代码。
     *
     * @param raw 原始文本，可为 null
     * @return 翻译后的文本，输入为 null 时返回空串
     */
    public static String color(String raw) {
        if (raw == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /**
     * 把带 {@code &} 颜色代码的文本转成 Adventure {@link Component}。
     *
     * @param raw 原始文本
     * @return 组件
     */
    public static Component component(String raw) {
        return SECTION_SERIALIZER.deserialize(color(raw));
    }

    /**
     * 把 {@link Component} 反向序列化成 {@code &} 颜色代码字符串。
     *
     * <p>用于把玩家物品的自定义名称 / Lore 塞进消息模板或发给 AI 的 JSON 里。</p>
     *
     * @param component 组件，可为 null
     * @return 带 {@code &} 颜色代码的字符串
     */
    public static String toAmpersand(Component component) {
        if (component == null) {
            return "";
        }
        // legacyAmpersand 会把文本里字面量的 & 转义成 &&，这里还原回来，
        // 免得消息模板把它当成颜色代码、或让 AI 看到多余的字符。
        return AMPERSAND_SERIALIZER.serialize(component).replace("&&", "&");
    }

    /**
     * 去掉颜色代码，得到纯文本（用于日志、或给玩家的无颜色提示）。
     *
     * @param raw 原始文本
     * @return 去掉 {@code §x} 之后的文本
     */
    public static String stripColor(String raw) {
        return ChatColor.stripColor(color(raw));
    }
}
