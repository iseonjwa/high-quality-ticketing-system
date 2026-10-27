"""실제 Compose 해석과 JVM 실행 환경의 경계를 검사한다."""
import importlib.util
import os
import json
from pathlib import Path
import sys
import subprocess
from tempfile import TemporaryDirectory
import unittest

SCRIPT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT))
from workspace_env import LOCAL_ENV_KEYS, load_local_env
spec = importlib.util.spec_from_file_location('run_app', SCRIPT / 'run-app.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DotenvTests(unittest.TestCase):
    def values(self, content, overrides=None):
        env = {key: value for key, value in os.environ.items() if key not in LOCAL_ENV_KEYS}
        env.update(overrides or {})
        with TemporaryDirectory(prefix='iseonjwa-env-test-') as directory:
            path = Path(directory) / '.env'
            path.write_text(content)
            return load_local_env(path, env)

    def test_double_quotes_and_inline_comments(self):
        result = self.values('DB_PASSWORD="review # = password" # comment\n')
        self.assertEqual('review # = password', result['DB_PASSWORD'])

    def test_single_quotes_keep_interpolation_literal(self):
        result = self.values("DB_PASSWORD='literal ${UNSET_VAR} # value'\n")
        self.assertEqual('literal ${UNSET_VAR} # value', result['DB_PASSWORD'])

    def test_escape_and_unquoted_comment(self):
        result = self.values('DB_PASSWORD="line\\nvalue"\nADMIN_EMAIL=admin@test.local # comment\n')
        self.assertEqual('line\nvalue', result['DB_PASSWORD'])
        self.assertEqual('admin@test.local', result['ADMIN_EMAIL'])

    def test_literal_dollar_count_is_preserved(self):
        result = self.values("DB_PASSWORD='one$two$$end${UNSET_VAR}$'\n")
        self.assertEqual('one$two$$end${UNSET_VAR}$', result['DB_PASSWORD'])

    def test_shell_environment_has_same_priority_as_compose(self):
        result = self.values('DB_PASSWORD="file value"\n', {'DB_PASSWORD': 'parent value'})
        self.assertEqual('parent value', result['DB_PASSWORD'])


class JvmEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.values = {key: 'test-only-value' for key in LOCAL_ENV_KEYS}

    def test_every_app_binds_main_and_management_to_loopback(self):
        for app in runner.APP_PORTS:
            with self.subTest(app=app):
                env = runner.build_app_environment(app, self.values, {})
                self.assertEqual('127.0.0.1', env['SERVER_ADDRESS'])
                self.assertEqual('127.0.0.1', env['MANAGEMENT_SERVER_ADDRESS'])
                self.assertEqual('127.0.0.1', env['EUREKA_INSTANCE_IPADDRESS'])
                self.assertEqual('true', env['EUREKA_INSTANCE_PREFERIPADDRESS'])

    def test_config_and_gateway_do_not_inherit_unneeded_secrets(self):
        for app in ['config-server', 'eureka', 'gateway']:
            with self.subTest(app=app):
                env = runner.build_app_environment(app, self.values, self.values.copy())
                self.assertNotIn('JWT_REFRESH_SECRET', env)
                self.assertNotIn('DB_PASSWORD', env)
                self.assertNotIn('ADMIN_PASSWORD', env)
        self.assertIn('JWT_ACCESS_SECRET', runner.build_app_environment('gateway', self.values, {}))

    def test_missing_required_value_fails_without_printing_values(self):
        self.values['DB_PASSWORD'] = ''
        with self.assertRaisesRegex(ValueError, 'DB_PASSWORD'):
            runner.build_app_environment('user-service', self.values, {})

    def test_compose_keeps_container_internal_bindings(self):
        env = os.environ.copy()
        env.update(self.values)
        result = json.loads(subprocess.check_output(['docker', 'compose', '-f', str(SCRIPT.parent/'compose.yml'),
                                                     'config', '--format', 'json'], env=env, text=True))
        for app in runner.APP_PORTS:
            with self.subTest(app=app):
                values = result['services'][app]['environment']
                self.assertEqual('0.0.0.0', values['SERVER_ADDRESS'])
                self.assertEqual('0.0.0.0', values['MANAGEMENT_SERVER_ADDRESS'])


if __name__ == '__main__':
    unittest.main()
