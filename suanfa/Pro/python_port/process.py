"""Python port of ``process.m`` (faithful translation)."""
from datetime import datetime


def process(T, A):
    t = datetime.now()
    time_percent = max(T) / A
    print("%s - %g" % (t, time_percent))
