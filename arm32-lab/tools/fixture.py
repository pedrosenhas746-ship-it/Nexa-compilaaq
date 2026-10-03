#!/usr/bin/env python3
"""Small independent ELF32 fixture; contains no game code or Meta binaries."""
import struct
from pathlib import Path
import sys

def fixture():
    data=bytearray(1024)
    data[:16]=b'\x7fELF\x01\x01\x01'+bytes(9)
    struct.pack_into('<HHIIIIIHHHHHH',data,16,3,40,1,0,52,0,0x05000000,52,32,2,0,0,0)
    struct.pack_into('<IIIIIIII',data,52,1,0,0,0,len(data),len(data),7,4096)
    strings=b'\0opus_get_version_string\0JNI_OnLoad\0'
    dynamic=[(5,0x280),(10,len(strings)),(6,0x240),(11,16),(4,0x2d0),(17,0x2f0),(18,8),(19,8),(0,0)]
    struct.pack_into('<IIIIIIII',data,84,2,0x300,0x300,0x300,len(dynamic)*8,len(dynamic)*8,6,4)
    # ARM: load relocated guest pointer, return. Independent expected string.
    struct.pack_into('<III',data,0x80,0xe59f0000,0xe12fff1e,0x200)
    struct.pack_into('<III',data,0x90,0xe5902000,0xe5923018,0xe12fff13)
    data[0x200:0x20d]=b'nexa fixture\0'
    data[0x280:0x280+len(strings)]=strings
    struct.pack_into('<IIIBBH',data,0x250,1,0x80,12,0x12,0,1)
    struct.pack_into('<IIIBBH',data,0x260,strings.index(b'JNI_OnLoad'),0x90,12,0x12,0,1)
    struct.pack_into('<IIIIII',data,0x2d0,1,3,1,0,2,0)
    struct.pack_into('<II',data,0x2f0,0x88,23)
    for i,(tag,value) in enumerate(dynamic):struct.pack_into('<iI',data,0x300+i*8,tag,value)
    return data

if __name__=='__main__':
    root=Path(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
    data=fixture();(root/'fixture.elf').write_bytes(data)
    (root/'truncated.elf').write_bytes(data[:64])
    data[4]=2;(root/'wrong-class.elf').write_bytes(data)
