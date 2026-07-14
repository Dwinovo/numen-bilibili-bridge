package com.dwinovo.bilibridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity constants for the numen-bilibili-bridge mod. The numen engine has its
 * own {@code com.dwinovo.numen.Constants}; this bridge lives in its own package
 * so the two mods never put the same fully-qualified class on the classpath.
 */
public final class Constants {

    public static final String MOD_ID = "numen_bilibili_bridge";
    public static final String MOD_NAME = "Numen Bilibili Bridge";
    public static final Logger LOG = LoggerFactory.getLogger("BiliBridge");

    private Constants() {}
}
