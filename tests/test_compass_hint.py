#!/usr/bin/env python3
"""Compile and exercise production compass geometry without a game server."""
import os
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else None
HARNESS = '''
import me.fallenbreath.tcuhc.util.CompassHint;
public class CompassHintTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String[] labels = {"↑ 前方", "↗ 右前方", "→ 右侧", "↘ 右后方", "↓ 后方", "↙ 左后方", "← 左侧", "↖ 左前方"};
        for (int yaw : new int[]{0, 90, 180, -90, 360, -720}) {
            for (int sector=0; sector<8; sector++) {
                double angle=Math.toRadians(yaw+sector*45);
                check(CompassHint.format(-120*Math.sin(angle),120*Math.cos(angle),yaw,8)
                    .equals("敌人 "+labels[sector]+" · 约 120 格 · 8 秒前定位"));
            }
        }
        check(CompassHint.format(0,0,0,0).equals("敌人 就在附近 · 不足 5 格 · 0 秒前定位"));
        check(CompassHint.format(0,4.9,0,0).contains("不足 5 格"));
        check(CompassHint.format(0,5,0,0).contains("约 10 格"));
        check(CompassHint.format(0,124,0,59).contains("约 120 格 · 59 秒前定位"));
        check(CompassHint.format(0,125,0,60).contains("约 130 格 · 60 秒前定位"));
        for (double angle : new double[]{22.4, -22.4}) {
            check(CompassHint.format(-100*Math.sin(Math.toRadians(angle)),100*Math.cos(Math.toRadians(angle)),0,0).contains("↑ 前方"));
        }
        check(CompassHint.format(-100*Math.sin(Math.toRadians(22.6)),100*Math.cos(Math.toRadians(22.6)),0,0).contains("↗ 右前方"));
        check(CompassHint.format(-100*Math.sin(Math.toRadians(-22.6)),100*Math.cos(Math.toRadians(-22.6)),0,0).contains("↖ 左前方"));
        System.out.println("Passed 57 compass geometry checks");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='tcuhc-compass-') as folder:
    harness = Path(folder) / 'CompassHintTest.java'
    harness.write_text(HARNESS, encoding='utf-8')
    subprocess.run([str(JAVA_BIN / 'javac') if JAVA_BIN else 'javac', '-encoding', 'UTF-8', '-d', folder,
                    str(ROOT / 'src/main/java/me/fallenbreath/tcuhc/util/CompassHint.java'), str(harness)], check=True)
    subprocess.run([str(JAVA_BIN / 'java') if JAVA_BIN else 'java', '-cp', folder, 'CompassHintTest'], check=True)
