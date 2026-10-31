import os
import sys

# tools/ on sys.path so tests can `import owlproto` and `import owlsync`.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
