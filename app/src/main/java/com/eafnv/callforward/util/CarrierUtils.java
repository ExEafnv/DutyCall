package com.eafnv.callforward.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CarrierUtils {

    public static final String CMCC = "CMCC";
    public static final String UNICOM = "UNICOM";
    public static final String TELECOM = "TELECOM";

    private static final Set<String> TELECOM_PREFIXES = new HashSet<>(Arrays.asList(
            "133","149","153","173","177","180","181","189","190","191","193","199"));

    private static final Set<String> UNICOM_PREFIXES = new HashSet<>(Arrays.asList(
            "130","131","132","145","155","156","166","167","171","175","176","185","186","196"));

    /** 根据号码前三位推断运营商。推断不准时，用户可在人员编辑里手动覆盖。 */
    public static String guessCarrier(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 3) return CMCC;
        String prefix = phoneNumber.substring(0, 3);
        if (TELECOM_PREFIXES.contains(prefix)) return TELECOM;
        if (UNICOM_PREFIXES.contains(prefix)) return UNICOM;
        return CMCC;
    }

    public static String displayName(String carrierKey) {
        if (TELECOM.equals(carrierKey)) return "电信";
        if (UNICOM.equals(carrierKey)) return "联通";
        return "移动";
    }

    public static String displayNameWithAuto(String carrierKey) {
        if (carrierKey == null || carrierKey.isEmpty()) return "自动推断";
        return displayName(carrierKey);
    }
}