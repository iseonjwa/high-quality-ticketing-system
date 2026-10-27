import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'check_port_exposure', Path(__file__).resolve().parent.parent / 'check-port-exposure.py')
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)


def config(**services):
    return {'services': {name: {'ports': ports} for name, ports in services.items()}}


def port(published, host_ip='127.0.0.1'):
    return {'published': str(published), 'host_ip': host_ip, 'target': published}


class PortExposureTest(unittest.TestCase):
    def test_gateway와_관리자_UI의_loopback_포트는_통과한다(self):
        result = check.violations(config(gateway=[port(8080)], grafana=[port(3000)]), base_only=True)
        self.assertEqual([], result)

    def test_기본_Compose에서_서비스_포트를_열면_위반이다(self):
        result = check.violations(config(**{'user-service': [port(8081)]}), base_only=True)
        self.assertEqual(1, len(result))
        self.assertIn('user-service', result[0])

    def test_local_override는_서비스_포트를_열어도_loopback이면_통과한다(self):
        result = check.violations(config(**{'user-service': [port(8081)], 'redis': [port(6379)]}), base_only=False)
        self.assertEqual([], result)

    def test_loopback이_아닌_주소는_어느_경우에도_위반이다(self):
        for host_ip in ['0.0.0.0', None, '192.168.0.2']:
            with self.subTest(host_ip=host_ip):
                result = check.violations(config(gateway=[port(8080, host_ip)]), base_only=False)
                self.assertEqual(1, len(result))

    def test_포트가_없는_서비스는_무시한다(self):
        self.assertEqual([], check.violations({'services': {'redis': {}}}, base_only=True))


if __name__ == '__main__':
    unittest.main()
