import gzip
import json


def read_file_jsongz(file_path):
  with gzip.open(file_path, 'rb') as f:
    ret = json.load(f)
    return ret


def write_file_jsongz(obj, file_path, **json_dump_opts):
  with gzip.open(file_path, 'wt', encoding='utf-8') as f:
    json.dump(obj, f, **json_dump_opts)
