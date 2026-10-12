"""Enter a repository PAT on one explicitly selected ADB-connected watch."""
import argparse
import getpass
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', choices=['cn.wordwatch.app', 'cn.wordwatch.app.dev'], default='cn.wordwatch.app')
    args = parser.parse_args()

    def adb(*parts, data=None):
        result = subprocess.run([args.adb, '-s', args.serial, *parts], input=data, capture_output=True)
        if result.returncode:
            raise RuntimeError('ADB 操作失败；请检查连接、设备授权和安装状态。')
        return result.stdout.decode('utf-8', errors='replace')

    if adb('get-state').strip() != 'device':
        raise RuntimeError('指定设备未连接或未授权。')
    token = getpass.getpass('GitHub 仓库 Token（隐藏输入，不写文件）：').strip()
    if not re.fullmatch(r'[A-Za-z0-9_]{20,255}', token):
        raise RuntimeError('Token 格式不符合 GitHub PAT，请核对后重试。')
    adb('shell', 'am', 'start', '-n', args.package + '/cn.wordwatch.app.MainActivity', '--es', 'screen', 'settings')
    time.sleep(1)

    def locate(attribute, value):
        for _ in range(18):
            adb('shell', 'uiautomator', 'dump', '/sdcard/wordwatch-config-ui.xml')
            raw = adb('exec-out', 'cat', '/sdcard/wordwatch-config-ui.xml')
            if '<?xml' not in raw:
                time.sleep(.5)
                continue
            root = ET.fromstring(raw[raw.index('<?xml'):])
            for node in root.iter('node'):
                if node.get(attribute) != value:
                    continue
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
            size = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
            width, height = map(int, size.groups())
            adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 4), '350')
        raise RuntimeError('未找到设置字段；请在手表打开设置后重试。')

    x, y = locate('content-desc', 'Access Token')
    adb('shell', 'input', 'tap', str(x), str(y))
    # Strictly validated ASCII secret goes through stdin, never command-line arguments.
    adb('shell', data=('input text ' + token + '\n').encode('ascii'))
    token = ''
    adb('shell', 'input', 'keyevent', '4')
    x, y = locate('text', '保存配置')
    adb('shell', 'input', 'tap', str(x), str(y))
    print('已输入并点击保存，请在手表确认同步结果。')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, ET.ParseError, FileNotFoundError) as error:
        print(str(error))
        raise SystemExit(1)
