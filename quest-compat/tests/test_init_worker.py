from pathlib import Path
import subprocess
import tempfile
import unittest


class InitWorkerTest(unittest.TestCase):
    def test_reinitialization_reuses_thread_and_completes_errors(self):
        include = Path(__file__).resolve().parents[1] / 'compat/gearvr/src'
        with tempfile.TemporaryDirectory() as temp:
            source, binary = Path(temp) / 'test.cpp', Path(temp) / 'test'
            source.write_text('''#include "init_worker.h"
            #include <cassert>
            #include <stdexcept>
            int main() {
                auto &worker = compatibility_layer::InitWorker::instance();
                std::thread::id first;
                assert(worker.submit([&] { first = std::this_thread::get_id(); return true; }).get());
                for (int i = 0; i < 50; ++i)
                    assert(worker.submit([&] { return std::this_thread::get_id() == first; }).get());
                assert(!worker.submit([] { return false; }).get());
                assert(!worker.submit([]() -> bool { throw std::runtime_error("failed"); }).get());
                assert(worker.submit([] { return true; }).get());
            }
            ''')
            subprocess.run(['c++', '-std=c++17', '-pthread', '-Wall', '-Wextra', '-Werror',
                            '-I', str(include), str(source), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=10)
