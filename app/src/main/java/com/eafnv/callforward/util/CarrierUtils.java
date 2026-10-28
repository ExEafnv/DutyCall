package com.eafnv.callforward.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 运营商识别与展示名映射。
 *
 * 只按号段做启发式推断，不保证准确；推断结果可以由用户在人员编辑里手动覆盖。
 */
public class CarrierUtils {

    /** 中国移动。 */
    public static final String CMCC = "CMCC";
    /** 中国联通。 */
    public static final String UNICOM = "UNICOM";
    /** 中国电信。 */
    public static final String TELECOM = "TELECOM";

    /** 电信号段，按号码前三位匹配。 */
    private static final Set<String> TELECOM_PREFIXES = new HashSet<>(Arrays.asList(
            "133","149","153","173","177","180","181","189","190","191","193","199"));

    /** 联通号段，按号码前三位匹配。 */
    private static final Set<String> UNICOM_PREFIXES = new HashSet<>(Arrays.asList(
            "130","131","132","145","155","156","166","167","171","175","176","185","186","196"));

    /** 根据号码前三位推断运营商。推断不准时，用户可在人员编辑里手动覆盖。 */
    public static String guessCarrier(String phoneNumber) {
        // 号码太短无法判断，按移动兜底
        if (phoneNumber == null || phoneNumber.length() < 3) return CMCC;
        String prefix = phoneNumber.substring(0, 3);
        if (TELECOM_PREFIXES.contains(prefix)) return TELECOM;
        if (UNICOM_PREFIXES.contains(prefix)) return UNICOM;
        return CMCC;
    }

    /** 运营商标识转中文展示名。 */
    public static String displayName(String carrierKey) {
        if (TELECOM.equals(carrierKey)) return "电信";
        if (UNICOM.equals(carrierKey)) return "联通";
        return "移动";
    }

    /** 展示名；标识为空时显示“自动推断”。 */
    public static String displayNameWithAuto(String carrierKey) {
        if (carrierKey == null || carrierKey.isEmpty()) return "自动推断";
        return displayName(carrierKey);
    }
}