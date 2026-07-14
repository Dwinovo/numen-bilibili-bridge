package com.dwinovo.bilibridge.bili;

/**
 * 手动联调入口：用真实的 {@link BiliDanmakuClient} 连一个直播间，把收到的每条
 * 命令（cmd 名）、每条弹幕/SC 和连接状态持续打印到控制台。不是单测，不会被
 * Gradle test 执行；排查「认证成功后收不到消息」这类问题时手动运行。
 *
 * <p>用法（仓库根目录）：
 * <pre>
 *   ./gradlew :common:compileTestJava
 *   java -cp "common/build/classes/java/test;common/build/classes/java/main;&lt;gson.jar&gt;;&lt;slf4j-api.jar&gt;;&lt;slf4j-simple.jar&gt;" \
 *        com.dwinovo.bilibridge.bili.ManualSmokeMain &lt;房间号&gt; [运行秒数]
 * </pre>
 * gson / slf4j-api / slf4j-simple 三个 jar 从本机 Gradle 缓存
 * （{@code ~/.gradle/caches/modules-2/files-2.1/}）里各取一个即可；Linux/macOS
 * 把 {@code -cp} 里的 {@code ;} 换成 {@code :}。Windows 上务必在 PowerShell/cmd
 * 里运行：Git Bash 会对含 {@code ;} 的 {@code -cp} 参数做路径转换，把
 * {@code C:/...} 的 jar 路径拆坏，jar 悄悄从 classpath 消失。房间号短号即可；
 * 不传秒数默认跑 180 秒。健康的连接应当：每 30 秒有一次心跳回应（inbound 归零、popularity
 * 更新），命令持续到达；对开播的热门房间还应源源不断打出 DANMU_MSG。
 */
public final class ManualSmokeMain {

    private ManualSmokeMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: ManualSmokeMain <房间号> [运行秒数=180]");
            System.exit(2);
        }
        long roomId = Long.parseLong(args[0]);
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 180;

        BiliDanmakuClient client = new BiliDanmakuClient(new BiliApi(), msg ->
                System.out.println("[msg] " + msg.kind() + " [" + msg.username() + "] " + msg.text()
                        + (msg.priceYuan() > 0 ? " ¥" + msg.priceYuan() : "")));
        client.commandObserver = cmd -> System.out.println("[cmd] " + cmd);
        client.start(roomId, "");

        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            System.out.println("[status] " + client.describe());
        }
        client.stop();
        Thread.sleep(500);
        System.out.println("[status] final: " + client.describe());
        System.exit(0);
    }
}
