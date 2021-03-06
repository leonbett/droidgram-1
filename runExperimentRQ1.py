import sys
import os
import signal
import subprocess
import shutil
import time
import multiprocessing
from os import listdir
from os.path import isfile, join
from joblib import Parallel, delayed

emulator_port = 5554
logback_config = """<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <!-- reduce the produced standard output in console for clarity, but all debug out is still in the produced log file -->
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
        <encoder>
            <pattern>%d{HH:mm:ss} %highlight(%-5level) %boldWhite%-20([%.18thread]) %cyan(%-35.35class{35}) %msg%n</pattern>
        </encoder>
    </appender>

    <timestamp key="myTimestamp" datePattern="yyyy-MM-dd'_'HH-mm-ss.SSS"/>
    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
            <layout class="ch.qos.logback.classic.html.HTMLLayout">
                <pattern>%d{HH:mm:ss.SSS}%thread%level%class%msg</pattern>
            </layout>
        </encoder>
        <file><DIR>/<NAME>.html</file>
        <append>true</append>
        <!-- set immediateFlush to false for much higher logging throughput -->
        <immediateFlush>true</immediateFlush>
    </appender>

    <root level="TRACE">
        <appender-ref ref="STDOUT" />
        <appender-ref ref="FILE" />
    </root>

</configuration>
"""


def get_next_emulator_port():
    global emulator_port
    ret = emulator_port
    emulator_port += 2
    return ret


def get_apk_files_from_dir(apk_dir):
    return [join(apk_dir, f) for f in listdir(apk_dir) if f.endswith(".apk") and isfile(join(apk_dir, f))]


def get_json_from_apk(apk_file):
    return apk_file.replace("-instrumented", "").replace(".apk", ".apk.json")


def init_all_experiments(apk_list):
    data = []

    for apk in apk_list:
        data.append(Data(apk))

    return data


class Data():
    def __init__(self, apk, seed=None, parent_avd_name=None):
        self.root_logs_dir = "./logs"
        self.root_grammar_input_dir = "./input"
        self.root_apks_dir = "./apks"
        self.root_output_dir = "./output"
        self.action_limit = 500
        self.nr_seeds = 10

        self.apk = apk
        self.json = get_json_from_apk(apk)
        self.emulator_port = get_next_emulator_port()
        self.emulator_name = "emulator-%d" % self.emulator_port
        self.avd_name = "emulator%d" % self.emulator_port

        self.emulator_pid = 0

        if seed is None:
            self.grammar_input_dir = join(self.root_grammar_input_dir, self.avd_name)
            self.output_dir = join(self.root_output_dir, self.avd_name)
            self.apk_dir = join(self.root_apks_dir, self.avd_name)
            self.logs_dir = join(self.root_logs_dir, self.avd_name)
            self.seed = ""
        else:
            self.seed = seed

            self.grammar_input_dir = join(self.root_grammar_input_dir, parent_avd_name, "seed%s" % seed)
            self.output_dir = join(self.root_output_dir, parent_avd_name, "seed%s" % seed)
            self.apk_dir = join(self.root_apks_dir, parent_avd_name)
            self.logs_dir = join(self.root_logs_dir, parent_avd_name, "seed%s" % seed)

        self.debug()
        self._clean_logs_dir()

    def __str__(self):
        return "Emulator: %s\tAPK %s" % (self.emulator_name, self.apk)

    def debug(self):
        print("APK %s" % self.apk)
        print("JSON %s" % self.json)
        print("Emulator: %s" % self.emulator_name)
        print("AVD Name: %s\n" % self.avd_name)
        print("Grammar input dir: %s" % self.grammar_input_dir)
        print("Output dir: %s" % self.output_dir)
        print("APKs dir: %s" % self.apk_dir)
        print("Logs dir: %s" % self.logs_dir)

    def _clean_logs_dir(self):
        try:
            shutil.rmtree(self.logs_dir)
        except:
            pass
        try:
            os.mkdir(self.root_logs_dir)
        except:
            pass
        try:
            os.mkdir(self.logs_dir)
        except:
            pass

    def _write_logback_config_files(self, name):
        try:
            global logback_config
            config = logback_config.replace("<DIR>", self.logs_dir).replace("<NAME>", name)
            with open("%s.xml" % name, "w") as f:
                f.write(config)
                f.close()
        except:
            pass

    def _run_command(self, command, file_name):
        print("Running command %s" % str(command))
        try:
            # process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE)
            # process.wait()
            os.system(*command)
            # output = process.stdout.readlines()

            # if file_name is not None:
            #    with open(join(self.logs_dir, file_name), "w") as f:
            #        f.write("Command %s\n" % str(command))
            #        f.write("Output:\n")
            #        for line in output:
            #            f.write(str(line))
            #        f.close()
        except Exception as e:
            print(e)
            print(e.args)
            raise e

    def _create_avd(self):
        self._run_command(["avdmanager "
                           "create "
                           "avd "
                           "-n "
                           " %s "
                           "-k "
                           "\"system-images;android-28;google_apis;x86\" "
                           "-d "
                           "pixel "
                           "--force " % self.avd_name
                           ],
                          "create_avd.log"
                          )

    def _delete_avd(self):
        self._run_command(["avdmanager "
                           "delete "
                           "avd "
                           "-n "
                           " %s " % self.avd_name
                           ],
                          "delete_avd.log"
                          )

    def _start_emulator(self):
        android_home = os.environ["ANDROID_HOME"]
        command = ["%s/emulator/emulator-headless "
                   "-avd "
                   " %s "
                   "-port "
                   " %d "
                   "-no-audio "
                   "-no-window "
                   "-no-snapshot " % (android_home, self.avd_name, self.emulator_port)]
        print("Starting emulator with command %s" % str(command))
        emulator_process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE)
        self.emulator_pid = emulator_process.pid

    def _copy_original_apk(self):
        try:
            shutil.rmtree(self.apk_dir)
        except:
            pass
        try:
            os.mkdir(self.root_apks_dir)
        except:
            pass
        try:
            os.mkdir(self.apk_dir)
        except:
            pass

        shutil.copy(self.apk, self.apk_dir)
        shutil.copy(self.json, self.apk_dir)

    def _clean_output_dir(self):
        try:
            shutil.rmtree(self.output_dir)
        except:
            pass
        try:
            os.mkdir(self.root_output_dir)
        except:
            pass
        try:
            os.mkdir(self.output_dir)
        except:
            pass

    def _clean_grammar_input_dir(self):
        try:
            shutil.rmtree(self.grammar_input_dir)
        except:
            pass
        try:
            os.mkdir(self.root_grammar_input_dir)
        except:
            pass
        try:
            os.mkdir(self.grammar_input_dir)
        except:
            pass

    def _run_droidgram_explore(self):
        log_name = "%s-01explore" % self.avd_name
        self._write_logback_config_files(log_name)
        command = ["./01.sh %s %s %d %s %s " % ("%s.xml" % log_name,
                                                self.apk_dir,
                                                self.action_limit,
                                                self.emulator_name,
                                                self.output_dir,
                                                )
                   ]
        self._run_command(command, None)

    def _step1_run_exploration(self):
        self._clean_output_dir()
        self._copy_original_apk()

        self._run_droidgram_explore()

    def _move_exploration_output_to_grammar_input(self):
        shutil.move("%s/droidMate" % self.output_dir, "%s/droidMate" % self.grammar_input_dir)

    def _run_droidgram_extraction(self):
        log_name = "%s-02extract" % self.avd_name
        self._write_logback_config_files(log_name)
        command = ["./02.sh %s %s %s/" % ("%s.xml" % log_name,
                                          self.grammar_input_dir,
                                          self.grammar_input_dir,
                                          )
                   ]
        self._run_command(command, None)

    def _step2_extract_grammar(self):
        self._clean_grammar_input_dir()
        self._move_exploration_output_to_grammar_input()
        self._run_droidgram_extraction()

    def _step3_fuzz_grammar(self):
        command = ["python3 "
                   "grammar_terminal_inputs.py %s %s %d " % (self.root_grammar_input_dir,
                                                             self.avd_name,
                                                             self.nr_seeds,
                                                             )
                   ]
        self._run_command(command, None)

        seeds = []
        print("processing dir %s" % self.grammar_input_dir)
        files = [join(self.grammar_input_dir, f) for f in os.listdir(self.grammar_input_dir)]
        print("found files %s" % str(files))
        for file in files:
            if isfile(file) and os.path.basename(file).startswith("inputs") and file.endswith(".txt"):
                print("processing seed file %s" % file)
                seed_nr = int(os.path.basename(file).replace("inputs", "").replace(".txt", ""))
                print("processing seed %d" % seed_nr)
                seed_dir = join(self.grammar_input_dir, "seed%d" % seed_nr)
                print("processing seed dir %s" % seed_dir)
                try:
                    os.mkdir(seed_dir)
                except:
                    pass
                shutil.copy(file, seed_dir)
                shutil.copy(join(self.grammar_input_dir, "grammar.txt"), seed_dir)
                shutil.copy(join(self.grammar_input_dir, "translationTable.txt"), seed_dir)
                print("copying output folder from %s to %s" % (join(self.grammar_input_dir, "droidMate"), join(seed_dir, "droidMate")))

                try:
                    shutil.rmtree(join(seed_dir, "droidMate"))
                except:
                    pass
                shutil.copytree(join(self.grammar_input_dir, "droidMate"), join(seed_dir, "droidMate"))

                seed = Data(self.apk, seed_nr, self.avd_name)
                seeds.append(seed)

        for seed in seeds:
            seed.start()

        print("Waiting 30 seconds for all emulators to start")
        time.sleep(10)
        print("Waiting 20 seconds for all emulators to start")
        time.sleep(10)
        print("Waiting 10 seconds for all emulators to start")
        time.sleep(10)
        print("Assuming that ALL emulators have started proceeding")

        num_cores = multiprocessing.cpu_count()
        Parallel(n_jobs=num_cores)(delayed(run_step4)(item) for item in seeds)

    def step4_run_grammar_inputs(self):
        log_name = "%s-04run" % self.avd_name
        self._write_logback_config_files(log_name)
        command = ["./04.sh %s %s %s %s %s " % ("%s.xml" % log_name,
                                                self.grammar_input_dir,
                                                self.apk_dir,
                                                self.output_dir,
                                                self.emulator_name,
                                                )
                   ]
        self._run_command(command, None)

    def start(self):
        self._create_avd()
        self._start_emulator()

    def execute(self):
        self._step1_run_exploration()
        self._step2_extract_grammar()
        self._step3_fuzz_grammar()

    def terminate(self):
        if self.emulator_pid > 0:
            print("Terminating emulator with pid %s" % self.emulator_pid)
            os.kill(self.emulator_pid, signal.SIGTERM)

        print("Deleting AVD %s" % self.avd_name)
        self._delete_avd()


def run_step4(item):
    try:
        item.step4_run_grammar_inputs()
    except Exception as e:
        print("Error `%s` when running the experiment in %s" % (str(e), item))

def run(item):
    try:
        item.execute()
    except Exception as e:
        print("Error `%s` when running the experiment in %s" % (str(e), item))


if __name__ == "__main__":
    apks_dir = sys.argv[1]
    apk_list = get_apk_files_from_dir(apks_dir)

    data = init_all_experiments(apk_list)

    for item in data:
        item.start()

    print("Waiting 30 seconds for all emulators to start")
    time.sleep(10)
    print("Waiting 20 seconds for all emulators to start")
    time.sleep(10)
    print("Waiting 10 seconds for all emulators to start")
    time.sleep(10)
    print("Assuming that ALL emulators have started proceeding")


    num_cores = multiprocessing.cpu_count()
    pool = multiprocessing.Pool(processes=num_cores)

    results = Parallel(n_jobs=num_cores)(delayed(run)(item) for item in data)

    for item in data:
        try:
            item.terminate()
        except Exception as e:
            print("Error `%s` stopping AVD in %s" % (str(e), item))

    # book keeping, make sure all emulators are dead
    os.system("killall /home/nataniel.borges/android/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless")