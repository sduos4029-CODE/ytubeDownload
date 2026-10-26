"""Apply Linux process limits outside the threaded HTTP process before yt-dlp."""
import resource
import runpy
import sys

resource.setrlimit(resource.RLIMIT_FSIZE, (4 * 1024 * 1024, 4 * 1024 * 1024))
sys.argv = sys.argv[1:]
runpy.run_path(sys.argv[0], run_name='__main__')
