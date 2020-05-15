import csv
import os
import json
from functools import reduce

# total statements
#apkjson = "/home/leon/HiWi/droidgram/orig/fork/droidgram-1/apks/com.mirkoo.apps.mislibros_231_apps.evozi.com.apk.json"
apkjson = "/home/leon/HiWi/droidgram/orig/fork/droidgram-1/apks/com.ajaxmobiletech.pocketlibrary_16020002_apps.evozi.com.apk.json"
with open(apkjson) as f:
    d = json.load(f)
    print("total statements: {}".format(len(d["allMethods"])))
# covered statements

cov_statements = set()
#suffix = "model/com.mirkoo.apps.mislibros/coverage.txt"
suffix = "model/com.ajaxmobiletech.pocketlibrary/coverage.txt"
cwd = "/home/leon/HiWi/droidgram/orig/fork/droidgram-1/merge-input-dir"
all_seed_dirs = [os.path.join(cwd, o) for o in os.listdir(cwd) 
                    if os.path.isdir(os.path.join(cwd,o)) and o.startswith("seed")]
all_coverage_files = sorted([os.path.join(d, suffix) for d in all_seed_dirs])


# print(all_seed_dirs)
# print(all_coverage_files)

cov_by_run = []
for f in all_coverage_files:
    print("Processing {}".format(f))
    with open(f, 'r') as csvfile:
        reader = csv.reader(csvfile, delimiter=';')
        run_cov = set()
        for row in reader:
            if row != []:
                #print("row: {}".format(row))
                run_cov.add(row[0])
		#cov_statements.add(row[0])        
        cov_by_run.append(run_cov)
        cov_statements = cov_statements | run_cov	

for i, s in enumerate(all_coverage_files):
    print("Report for {}".format(s))
    print("{} \t (# of covered statements)".format(len(cov_by_run[i])))
    print("{} \t (# of exclusively covered statements)".format(len(cov_by_run[i] - reduce(lambda x,y: x|y, [other for j, other in enumerate(cov_by_run) if j!=i]))))

print("\n\n{} \t (# Total combined unique covered statements)".format(len(cov_statements)))
#print("total cov statements: {}".format(cov_statements))
#print("size of set: {}".format(len(cov_statements)))
