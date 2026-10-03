"""Paths and global constants. The raw data directory is configurable through the DATA_DIR environment variable."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = Path(os.environ.get('DATA_DIR', '/home/user/Temp/data'))
MODELS_DIR = Path(os.environ.get('MODELS_DIR', ROOT / 'models'))
SUB_DIR = Path(os.environ.get('SUB_DIR', ROOT / 'submissions'))

GENERAL = DATA_DIR / 'General Data'
TRAIN = DATA_DIR / 'Training Data'
TEST = DATA_DIR / 'Test Data'
TEMPLATES = DATA_DIR / 'Submission Templates'

# Blend weights agreed after cross-fold selection (chosen on one time fold, verified on the other).
W_A_LATE = 0.4      # final late prob  = 0.4 * A + 0.6 * B
W_A_SVC = 0.3       # final service    = 0.3 * A + 0.7 * B
N_THREADS = int(os.environ.get('N_THREADS', '4'))
