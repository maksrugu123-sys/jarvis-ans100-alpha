"""Tests for the seek-based positional I/O fallback used on Windows."""
import os
import tempfile
import threading
import unittest

from .platform_io import lock_file, read_at_seek, unlock_file, write_all_at_seek


class SeekBasedIOTests(unittest.TestCase):
    def test_write_and_read_at_offsets_without_corrupting_neighbors(self):
        with tempfile.TemporaryFile() as stream:
            fd = stream.fileno()
            os.write(fd, b"abcdefghijklmnop")
            write_all_at_seek(fd, b"XYZ", 5)
            self.assertEqual(read_at_seek(fd, 16, 0), b"abcdeXYZijklmnop")
            self.assertEqual(read_at_seek(fd, 3, 13), b"nop")

    def test_read_at_returns_short_data_at_eof(self):
        with tempfile.TemporaryFile() as stream:
            fd = stream.fileno()
            os.write(fd, b"small")
            self.assertEqual(read_at_seek(fd, 20, 2), b"all")

    def test_zero_length_and_empty_write(self):
        with tempfile.TemporaryFile() as stream:
            fd = stream.fileno()
            write_all_at_seek(fd, b"", 0)
            self.assertEqual(read_at_seek(fd, 0, 0), b"")

    def test_multiple_handles_are_serialized_inside_one_process(self):
        with tempfile.TemporaryFile() as stream:
            first_fd = stream.fileno()
            second_fd = os.dup(first_fd)
            attempted = threading.Event()
            acquired = threading.Event()

            def contender():
                attempted.set()
                lock_file(second_fd, 2)
                try:
                    acquired.set()
                finally:
                    unlock_file(second_fd)

            lock_file(first_fd, 2)
            worker = threading.Thread(target=contender)
            try:
                worker.start()
                self.assertTrue(attempted.wait(1))
                self.assertFalse(acquired.wait(0.05))
            finally:
                unlock_file(first_fd)
            worker.join(2)
            os.close(second_fd)
            self.assertTrue(acquired.is_set())


if __name__ == "__main__":
    unittest.main()
