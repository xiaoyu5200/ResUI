package com.resui.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** ResidenceCache: 保存服务端推送的领地快照（自己的 / 公共可传送的）。 */
public final class ResidenceCache {
    public static final ResidenceCache INSTANCE = new ResidenceCache();

    /** 操作反馈的显示时长（毫秒）。 */
    private static final long FEEDBACK_MILLIS = 4000L;

    private volatile List<ResidenceEntry> own = List.of();
    private volatile List<ResidenceEntry> publicList = List.of();
    private volatile List<FlagOption> allFlags = List.of();
    private volatile long updatedAt;
    private volatile String error = "";
    private volatile String feedback = "";
    private volatile boolean feedbackOk;
    private volatile long feedbackAt;

    private ResidenceCache() {
    }

    public void updateFromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            own = readList(root, "own");
            publicList = readList(root, "public");
            List<FlagOption> flags = readFlagOptions(root, "allFlags");
            if (!flags.isEmpty()) {
                allFlags = flags;
            }
            error = root.has("error") ? root.get("error").getAsString() : "";
            if (root.has("result") && root.get("result").isJsonObject()) {
                JsonObject result = root.getAsJsonObject("result");
                setFeedback(result.has("ok") && !result.get("ok").isJsonNull() && result.get("ok").getAsBoolean(),
                        result.has("message") && !result.get("message").isJsonNull()
                                ? result.get("message").getAsString() : "");
            }
            updatedAt = System.currentTimeMillis();
        } catch (Throwable throwable) {
            error = "数据解析失败: " + throwable.getMessage();
        }
    }

    /** setFeedback: 记录一条操作反馈，供界面顶部提示条显示。 */
    public void setFeedback(boolean ok, String message) {
        feedbackOk = ok;
        feedback = message == null ? "" : message;
        feedbackAt = System.currentTimeMillis();
    }

    /** feedback: 仍在显示期内的反馈文案（含「成功 / 失败」前缀），过期后返回空串。 */
    public String feedback() {
        if (feedback.isBlank()) {
            return "";
        }
        if (System.currentTimeMillis() - feedbackAt > FEEDBACK_MILLIS) {
            return "";
        }
        return (feedbackOk ? "[成功] " : "[失败] ") + feedback;
    }

    public boolean feedbackOk() {
        return feedbackOk;
    }

    private static List<FlagOption> readFlagOptions(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return List.of();
        }
        List<FlagOption> result = new ArrayList<>();
        for (var element : root.getAsJsonArray(key)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = object.has("name") && !object.get("name").isJsonNull()
                    ? object.get("name").getAsString() : "";
            if (name.isBlank()) {
                continue;
            }
            String label = object.has("label") && !object.get("label").isJsonNull()
                    ? object.get("label").getAsString() : name;
            result.add(new FlagOption(name, label));
        }
        return List.copyOf(result);
    }

    private static List<ResidenceEntry> readList(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = root.getAsJsonArray(key);
        List<ResidenceEntry> entries = new ArrayList<>(array.size());
        for (var element : array) {
            if (element.isJsonObject()) {
                entries.add(ResidenceEntry.fromJson(element.getAsJsonObject()));
            }
        }
        return List.copyOf(entries);
    }

    public List<ResidenceEntry> own() {
        return own;
    }

    public List<ResidenceEntry> publicList() {
        return publicList;
    }

    /** allFlags: 服务端推来的全部权限项（含中文说明），用于权限列表完整展示。 */
    public List<FlagOption> allFlags() {
        return allFlags;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String error() {
        return error;
    }

    public boolean isEmpty() {
        return own.isEmpty() && publicList.isEmpty();
    }
}