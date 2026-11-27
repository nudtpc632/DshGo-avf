import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/ui/engine/engine_status.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  tearDown(() => AppLocale.use(AppLocale.zhCN));

  test('upgrade failures explain preservation and backup in both languages', () {
    for (final locale in [AppLocale.zhCN, AppLocale.en]) {
      AppLocale.use(locale);
      expect(humanizeRuntimeError('install failed: runtime_upgrade_blocked:base'), appL10n.phoneRuntimeUpgradeBase);
      for (final reason in ['transaction', 'legacy_interrupted']) {
        expect(
          humanizeRuntimeError('install failed: runtime_upgrade_blocked:$reason'),
          appL10n.phoneRuntimeUpgradeInterrupted,
        );
      }
      expect(
        humanizeRuntimeError('install failed: runtime_upgrade_blocked:symlink'),
        appL10n.phoneRuntimeUpgradeSymlink,
      );
      expect(
        humanizeRuntimeError('install failed: runtime_upgrade_blocked:running'),
        appL10n.phoneRuntimeUpgradeRunning,
      );
    }
  });

  test('unrecognized upgrade details are not echoed into UI', () {
    expect(
      humanizeRuntimeError('install failed: runtime_upgrade_blocked:private-path-token-fixture'),
      appL10n.phoneRuntimeUpgradeManifest,
    );
    expect(humanizeRuntimeError('install failed: unknown native message'), isNull);
  });

  test('setup failure body: only an unpack failure reads "extract failed"', () {
    for (final locale in [AppLocale.zhCN, AppLocale.en]) {
      AppLocale.use(locale);
      // No error text, or a real install error: the archive/unpack line.
      expect(setupFailureBody(null), appL10n.shellRuntimeExtractFailed);
      expect(setupFailureBody(''), appL10n.shellRuntimeExtractFailed);
      expect(setupFailureBody('install failed: unknown native message'), appL10n.shellRuntimeExtractFailed);
      // A start error that raced the install (PRoot -> AVF switch, image not yet
      // present) is an engine start problem, not a broken archive.
      expect(setupFailureBody('AVF image not installed'), appL10n.phoneEngineStartupFailed);
      expect(setupFailureBody('runtime not installed'), appL10n.phoneEngineRuntimeMissing);
      expect(setupFailureBody('start failed: no startup token received within 180s'), appL10n.phoneStartupTokenTimeout);
      // A known install error still gets its specific humanized line.
      expect(
        setupFailureBody('install failed: 存储空间不足：需要约 640 MB，当前可用 212 MB'),
        appL10n.phoneEngineStorageDetails('640', '212'),
      );
    }
  });
}
