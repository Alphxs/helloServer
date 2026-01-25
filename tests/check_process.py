import requests
import time
import threading
import os
import subprocess
import re
from datetime import datetime
import itertools
import sys
import queue

# --- Configuration ---
PHONE_IP = "192.168.50.238"
SERVER_PORT = 8080
BASE_URL = f"http://{PHONE_IP}:{SERVER_PORT}"
CLIENT_LOG_FILE = "client_output.log"
SERVER_LOG_FILE = "logcat_output.log"
MERGED_LOG_FILE = "merged_test_run.log"

LOGCAT_TAGS = "TestServer ImageRecognizer"

RECOGNIZE_URL = f"{BASE_URL}/recognize"
STATUS_URL = f"{BASE_URL}/status"
IMAGE_FILES = ["test_image_1.jpg", "test_image_2.jfif"]

stop_short_tasks = threading.Event()
log_queue = queue.Queue()

def logger_thread_func(log_file):
    """A dedicated thread to write logs from a queue to a file and stdout."""
    # Explicitly open the file with UTF-8 encoding to support all characters
    with open(log_file, "w", encoding="utf-8") as f: # <-- FIX
        while True:
            message = log_queue.get()
            if message is None:
                break

            print(message)
            f.write(message + "\n")
            f.flush()

def log_message(message):
    log_queue.put(message)

def get_timestamp():
    return datetime.now().strftime('%H:%M:%S.%f')[:-3]

def make_long_recognition_request(task_id, image_path):
    thread_name = f"LONG_TASK_{task_id}"
    start_time = time.time()
    try:
        if not os.path.exists(image_path):
            raise FileNotFoundError(f"'{image_path}' not found.")

        with open(image_path, 'rb') as f:
            files = {'imageFile': (image_path, f, 'image/jpeg')}
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ---> Sending request...")
            response = requests.post(RECOGNIZE_URL, files=files, timeout=60)
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] <--- Response received ({response.status_code}).")
            response.raise_for_status()
            end_time = time.time()
            duration = end_time - start_time
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ✅✅✅ Fully completed in {duration:.2f} seconds.")

    except Exception as e:
        end_time = time.time()
        duration = end_time - start_time
        log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌❌❌ ERROR after {duration:.2f} seconds: {e}")

def short_task_looper(looper_id):
    for i in itertools.count(1):
        if stop_short_tasks.is_set():
            log_message(f"[{get_timestamp()}] [CLIENT] [LOOPER_{looper_id}] Stop signal received. Halting.")
            break

        thread_name = f"L{looper_id}_QUICK_{i}"
        try:
            response = requests.get(STATUS_URL, timeout=2)
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ✅ Completed ({response.status_code}).")
        except Exception as e:
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌ ERROR: {e}")

        time.sleep(0.5 + (looper_id * 0.1))

def merge_and_sort_logs():
    print("\n" + "="*80)
    print("--- MERGING AND SORTING LOGS ---")

    all_logs = []
    client_regex = re.compile(r'\[(\d{2}:\d{2}:\d{2}\.\d{3})\]')
    server_regex = re.compile(r'\d{2}-\d{2}\s(\d{2}:\d{2}:\d{2}\.\d{3})')

    def parse_log_file(filepath, regex, source_tag):
        try:
            # Read files using UTF-8 to handle any character
            with open(filepath, 'r', encoding="utf-8") as f: # <-- FIX
                for line in f:
                    match = regex.search(line)
                    if match:
                        timestamp_str = match.group(1)
                        try:
                            dt_obj = datetime.strptime(timestamp_str, '%H:%M:%S.%f')
                            yield (dt_obj, f"[{source_tag}] {line.strip()}")
                        except ValueError:
                            continue
        except FileNotFoundError:
            print(f"Warning: '{filepath}' not found.")

    all_logs.extend(parse_log_file(CLIENT_LOG_FILE, client_regex, "CLIENT"))
    all_logs.extend(parse_log_file(SERVER_LOG_FILE, server_regex, "SERVER"))

    all_logs.sort(key=lambda x: x[0])

    # Write the final merged log using UTF-8
    with open(MERGED_LOG_FILE, "w", encoding="utf-8") as f: # <-- FIX
        for _, line in all_logs:
            print(line)
            f.write(line + "\n")
    print(f"--- MERGED LOGS SAVED TO '{MERGED_LOG_FILE}' ---")
    print("="*80 + "\n")

if __name__ == "__main__":
    for logfile in [CLIENT_LOG_FILE, SERVER_LOG_FILE, MERGED_LOG_FILE]:
        if os.path.exists(logfile):
            os.remove(logfile)

    logger = threading.Thread(target=logger_thread_func, args=(CLIENT_LOG_FILE,))
    logger.start()

    log_message("--- Starting Full End-to-End Test ---")
    log_message(f"[{get_timestamp()}] [SYSTEM] Starting ADB logcat capture to '{SERVER_LOG_FILE}'...")

    subprocess.run(["adb", "logcat", "-c"], check=True, capture_output=True)

    adb_command = ["adb", "logcat", "-s"] + LOGCAT_TAGS.split()
    # Open the stdout file with UTF-8 encoding for the subprocess
    logcat_file_handle = open(SERVER_LOG_FILE, 'w', encoding="utf-8") # <-- FIX
    logcat_process = subprocess.Popen(adb_command, stdout=logcat_file_handle, stderr=subprocess.PIPE)
    time.sleep(1)

    for img_file in IMAGE_FILES:
        if not os.path.exists(img_file):
            open(img_file, 'a').close()

    log_message(f"--- Testing High Concurrency (5 Long, 5 Short) Workload at {BASE_URL} ---")
    log_message(f"Start time: {get_timestamp()}\n")

    long_task_threads = []
    short_task_threads = []

    for i in range(1, 6):
        image_to_use = IMAGE_FILES[(i - 1) % len(IMAGE_FILES)]
        long_task_threads.append(threading.Thread(target=make_long_recognition_request, args=(i, image_to_use)))
        short_task_threads.append(threading.Thread(target=short_task_looper, args=(i,)))

    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- Starting 5 long tasks... ---")
    for thread in long_task_threads:
        thread.start()
        time.sleep(1)

    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- Starting 5 continuous loops of quick tasks... ---")
    for thread in short_task_threads:
        thread.start()

    for thread in long_task_threads:
        thread.join()

    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- All long tasks finished. Signaling loopers to stop. ---")
    stop_short_tasks.set()

    for thread in short_task_threads:
        thread.join()

    log_message(f"\n--- Test Script Finished at {get_timestamp()} ---")

    log_message(f"[{get_timestamp()}] [SYSTEM] Terminating ADB logcat capture...")
    logcat_process.terminate()
    try:
        logcat_process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        logcat_process.kill()
    logcat_file_handle.close() # Close the file handle for the subprocess
    log_message(f"[{get_timestamp()}] [SYSTEM] Logcat capture stopped.")

    log_queue.put(None)
    logger.join()

    merge_and_sort_logs()

    print("\n✅ Done. ✅")

