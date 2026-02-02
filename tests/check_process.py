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
PHONE_IP = "192.168.50.124"
SERVER_PORT = 8080
BASE_URL = f"http://{PHONE_IP}:{SERVER_PORT}"
CLIENT_LOG_FILE = "client_output.log"
SERVER_LOG_FILE = "logcat_output.log"
MERGED_LOG_FILE = "merged_test_run.log"

LOGCAT_TAGS = "TestServer ImageRecognizer"

# --- NEW: Updated URL Endpoints ---
RECOGNIZE_URL = f"{BASE_URL}/recognize"
QUEUE_STATUS_URL = f"{BASE_URL}/queue-status"  # For checking active/queued tasks
RESULT_URL_BASE = f"{BASE_URL}/result"       # For polling for the result of a specific task
CONCURRENCY_URL = f"{BASE_URL}/set-concurrency" # For setting the thread limit

IMAGE_FILES = ["test_image_1.jpg", "test_image_2.jfif"]

stop_short_tasks = threading.Event()
log_queue = queue.Queue()

def logger_thread_func(log_file):
    """A dedicated thread to write logs from a queue to a file and stdout."""
    with open(log_file, "w", encoding="utf-8") as f:
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

def poll_for_result(task_id_from_server, thread_name, start_time):
    """UPDATED: Polls the result endpoint until the task is complete or fails."""
    poll_url = f"{RESULT_URL_BASE}/{task_id_from_server}"
    poll_interval = 2  # seconds
    max_polls = 15 #  timeout

    log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}]  Polling URL: {poll_url}")

    for i in range(max_polls):
        try:
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}]  ...polling attempt {i+1}/{max_polls}")
            response = requests.get(poll_url)

            if response.status_code == 404:
                log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ...task not yet found (404), will retry.")
                time.sleep(poll_interval)
                continue # Explicitly continue to the next loop iteration

            response.raise_for_status()
            result_data = response.json()
            status = result_data.get("status")

            # --- LOGIC FIX STARTS HERE ---
            if status == "complete":
                duration = time.time() - start_time
                log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ✅✅✅ SUCCESS in {duration:.2f}s. Result: {result_data}")
                return # EXIT a: Success
            
            elif status == "error":
                duration = time.time() - start_time
                error_msg = result_data.get("message", "Unknown error")
                log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌❌❌ SERVER-SIDE ERROR after {duration:.2f}s: {error_msg}")
                return # EXIT b: Server-side error
            
            # If the status is "queued", "processing", or anything else, log it and continue.
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ...status is '{status}'. Waiting for {poll_interval}s...")
            time.sleep(poll_interval)
            # No 'continue' needed here, the loop will naturally proceed.
            # --- LOGIC FIX ENDS HERE ---

        except Exception as e:
            # This block now only catches client-side errors during the request.
            duration = time.time() - start_time
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌❌❌ POLLING FAILED after {duration:.2f}s: {e}")
            return # EXIT c: Client-side error

    # If the for loop finishes without returning, it means we have timed out.
    duration = time.time() - start_time
    log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌❌❌ TIMEOUT after {duration:.2f}s waiting for result.")


def make_long_recognition_request(task_id, image_path):
    """UPDATED: Handles the async task submission and then triggers polling."""
    thread_name = f"LONG_TASK_{task_id}"
    start_time = time.time()
    max_retries = 15
    retry_delay = 2  # seconds
    try:
        if not os.path.exists(image_path):
            raise FileNotFoundError(f"'{image_path}' not found.")

        for attempt in range(max_retries):
            try:
                with open(image_path, 'rb') as f:
                    files = {'imageFile': (image_path, f, 'image/jpeg')}

                    if attempt > 0:
                        log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ---> Retrying request (Attempt {attempt + 1}/{max_retries})...")
                    else:
                        log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ---> Sending request...")

                    # Send the initial request to queue the task
                    response = requests.post(RECOGNIZE_URL, files=files)
                    log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] <--- Initial response received ({response.status_code}).")

                    # If successful (202), process the response and break the loop
                    if response.status_code == 202:
                        response_data = response.json()
                        task_id_from_server = response_data.get("taskId")
                        if task_id_from_server:
                            # The plot_timeline.py script looks for this exact message format
                            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] Got Task ID from server: {task_id_from_server}")

                            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] Task accepted. Starting to poll...")
                            poll_for_result(task_id_from_server, thread_name, start_time)
                            return  # --- Task successfully submitted, exit the function ---
                        else:
                            # This is a server error if it sends 202 without a task ID
                            raise ValueError("Server accepted request (202) but did not return a taskId.")

                    # If status is not 202, it's a submission error. We will retry after a delay.
                    log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ⚠️ UNEXPECTED STATUS: Expected 202, Got {response.status_code}. Will retry in {retry_delay}s...")

            except requests.exceptions.RequestException as e:
                # This catches temporary network errors during submission
                log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ⚠️ SUBMISSION FAILED (Attempt {attempt + 1}/{max_retries}): {e}. Will retry in {retry_delay}s...")

            # If the code reaches here, it means the submission was not successful. Wait before the next attempt.
            time.sleep(retry_delay)

        # --- NEW: If the loop finishes, all retries have failed ---
        log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌❌❌ FATAL: Could not get task accepted after {max_retries} attempts.")


    except Exception as e:
        duration = time.time() - start_time
        log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌❌❌ REQUEST FAILED after {duration:.2f} seconds: {e}")


def short_task_looper(looper_id):
    """UPDATED: Periodically hits the /queue-status endpoint."""
    for i in itertools.count(1):
        if stop_short_tasks.is_set():
            # ... (stops the loop)
            break

        thread_name = f"L{looper_id}_STATUS_{i}"
        try:
            # Calls the /queue-status endpoint
            response = requests.get(QUEUE_STATUS_URL)
            response.raise_for_status()

            # Parses the JSON response
            status_data = response.json()
            active = status_data.get('activeProcessingTasks', 'N/A')
            queued = status_data.get('queuedWaitingTasks', 'N/A')

            # Logs the formatted message
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ✅ Queue Status OK: Active={active}, Queued={queued}")
        except Exception as e:
            log_message(f"[{get_timestamp()}] [CLIENT] [{thread_name}] ❌ Queue Status ERROR: {e}")

        # Waits before the next poll
        time.sleep(1.5 + (looper_id * 0.1))


def set_server_concurrency(max_threads):
    """NEW: Sets the max concurrency on the server via the new endpoint."""
    log_message(f"[{get_timestamp()}] [SYSTEM] --- Setting server max concurrency to {max_threads}... ---")
    try:
        # --- FIX: Send raw JSON, not form data ---
        # The server's default JSON parser expects a raw JSON body.
        # The `json` parameter in requests handles this automatically by setting
        # the correct 'Content-Type: application/json' header.
        json_payload = {"maxThreads": max_threads}
        response = requests.post(CONCURRENCY_URL, json=json_payload)
        # --- END FIX ---

        response.raise_for_status()
        log_message(f"[{get_timestamp()}] [SYSTEM] ✅ Server concurrency set to {max_threads}.")
    except Exception as e:
        log_message(f"[{get_timestamp()}] [SYSTEM] ❌ FAILED to set server concurrency: {e}")


def merge_and_sort_logs():
    """Merges client and server logs chronologically."""
    print("\n" + "="*80)
    print("--- MERGING AND SORTING LOGS ---")

    all_logs = []
    client_regex = re.compile(r'\[(\d{2}:\d{2}:\d{2}\.\d{3})\]')
    server_regex = re.compile(r'\d{2}-\d{2}\s(\d{2}:\d{2}:\d{2}\.\d{3})')

    def parse_log_file(filepath, regex, source_tag):
        try:
            with open(filepath, 'r', encoding="utf-8") as f:
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

    with open(MERGED_LOG_FILE, "w", encoding="utf-8") as f:
        for _, line in all_logs:
            print(line)
            f.write(line + "\n")
    print(f"--- MERGED LOGS SAVED TO '{MERGED_LOG_FILE}' ---")
    print("="*80 + "\n")


if __name__ == "__main__":
    # --- Setup: Clear old logs and start the logger thread ---
    for logfile in [CLIENT_LOG_FILE, SERVER_LOG_FILE, MERGED_LOG_FILE]:
        if os.path.exists(logfile):
            os.remove(logfile)
    # This was previously inside the loop, it should be outside
    logger = threading.Thread(target=logger_thread_func, args=(CLIENT_LOG_FILE,))
    logger.start()

    log_message("--- Starting Full End-to-End Test ---")
    log_message(f"[{get_timestamp()}] [SYSTEM] Starting ADB logcat capture to '{SERVER_LOG_FILE}'...")

    # --- Setup: Start ADB log capture ---
    subprocess.run(["adb", "logcat", "-c"], check=True, capture_output=True)
    adb_command = ["adb", "logcat", "-s"] + LOGCAT_TAGS.split()
    logcat_file_handle = open(SERVER_LOG_FILE, 'w', encoding="utf-8")
    logcat_process = subprocess.Popen(adb_command, stdout=logcat_file_handle, stderr=subprocess.PIPE)
    time.sleep(1)

    # --- Setup: Create dummy image files and set server concurrency ---
    for img_file in IMAGE_FILES:
        if not os.path.exists(img_file):
            log_message(f"[{get_timestamp()}] [SYSTEM] Creating dummy file for {img_file}")
            with open(img_file, 'wb') as f: f.write(b'\0')
    set_server_concurrency(1)

    # --- Test Execution ---
    log_message(f"--- Testing Low Concurrency (10 Long, 10 Short) Workload at {BASE_URL} ---")
    log_message(f"Start time: {get_timestamp()}\n")

    long_task_threads = []
    short_task_threads = []
    for i in range(1, 2):
        image_to_use = IMAGE_FILES[(i - 1) % len(IMAGE_FILES)]
        # short_task_threads.append(threading.Thread(target=short_task_looper, args=(i,)))
        long_task_threads.append(threading.Thread(target=make_long_recognition_request, args=(i, image_to_use)))

    # --- FIX: Start the status loopers FIRST ---
    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- Starting continuous loops of queue status tasks... ---")
    for thread in short_task_threads:
        thread.start()
    time.sleep(1) # Give loopers a moment to start and establish a baseline

    # --- Now, start the main workload ---
    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- Starting long recognition tasks... ---")
    for thread in long_task_threads:
        thread.start()
        time.sleep(0.2) # Stagger requests slightly

    # --- Teardown: Wait for all threads to complete ---
    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- Main thread is now waiting for all long tasks to finish polling... ---")
    for thread in long_task_threads:
        thread.join()

    log_message(f"[{get_timestamp()}] [CLIENT] [MAIN] --- All long tasks finished. Signaling status loopers to stop. ---")
    stop_short_tasks.set()
    for thread in short_task_threads:
        thread.join()

    log_message(f"\n--- Test Script Finished at {get_timestamp()} ---")

    # --- Teardown: Stop logging ---
    log_message(f"[{get_timestamp()}] [SYSTEM] Terminating ADB logcat capture...")
    logcat_process.terminate()
    try:
        logcat_process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        logcat_process.kill()
    logcat_file_handle.close()
    log_message(f"[{get_timestamp()}] [SYSTEM] Logcat capture stopped.")

    log_queue.put(None)
    logger.join()

    # --- Final Step: Merge and display logs ---
    merge_and_sort_logs()
    print("\n✅ Done. ✅")

