import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
if sys.argv[2] not in text or 'RUNTIME CHECKS FAILED' in text:
    raise SystemExit('Instrumentation did not report the required pass marker: ' + sys.argv[2])
