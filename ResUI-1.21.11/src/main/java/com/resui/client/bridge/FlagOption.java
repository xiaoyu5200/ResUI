package com.resui.client.bridge;

/** FlagOption: 服务端推来的一个权限项（英文 flag 名 + 中文说明）。 */
public record FlagOption(String name, String label) {

    /** display: 用于列表展示的文本（中文说明优先）。 */
    public String display() {
        return label == null || label.isBlank() ? name : label;
    }
}
