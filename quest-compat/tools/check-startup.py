import subprocess,time,pathlib
deadline=time.monotonic()+12
while time.monotonic()<deadline:
    time.sleep(0.5)
log=subprocess.check_output(['adb','logcat','-d'],text=True)
pathlib.Path('startup-log.txt').write_text(log)
resumed=subprocess.check_output(['adb','shell','dumpsys','activity','activities'],text=True)
pathlib.Path('startup-activities.txt').write_text(resumed)
if 'Process: com.nexa.xr.debug' in log or not any('com.nexa.xr.debug' in line for line in resumed.splitlines() if 'ResumedActivity' in line or 'topResumedActivity' in line):
    print(log[-20000:])
    raise SystemExit('Original NEXA did not stay open')
print('Original NEXA main activity stayed open for 12 seconds')