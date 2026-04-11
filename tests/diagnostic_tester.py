import requests
import threading
import time
import os

# --- Configuration ---
SERVER_IP = "192.168.50.4"  # Android device IP address
RECOGNIZE_URL = f"http://{SERVER_IP}:8080/recognize"
SET_CONCURRENCY_URL = f"http://{SERVER_IP}:8080/set-concurrency"
GET_MAX_THREADS_URL = f"http://{SERVER_IP}:8080/get-max-threads"

# Diagnostic settings
DEFAULT_START_THREADS = 5
MAX_TEST_LIMIT = 40
STEP = 2

# RESOLVE ABSOLUTE PATH TO PREVENT DUMMY FILE ERRORS
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def get_timestamp():
    return time.strftime("%H:%M:%S", time.localtime())

def log_message(message):
    print(f"[{get_timestamp()}] {message}")

def get_valid_image_path():
    """Finds the real image in the tests directory, ignoring dummy files."""
    # Check for the specific file the user mentioned
    target = os.path.join(SCRIPT_DIR, "test_image_1.jpg")

    if os.path.exists(target):
        size = os.path.getsize(target)
        if size > 1000: # Ensure it's not the 4-byte dummy
            return target
        else:
            log_message(f"Warning: Found 4-byte dummy at {target}. Searching for alternatives...")

    # Fallback: search for any other real image in the tests folder
    for file in os.listdir(SCRIPT_DIR):
        if file.lower().endswith(('.jpg', '.jpeg', '.jfif', '.png')):
            path = os.path.join(SCRIPT_DIR, file)
            if os.path.getsize(path) > 1000:
                return path

    return None

def set_server_concurrency(num_threads):
    try:
        params = {"maxThreads": num_threads}
        response = requests.post(SET_CONCURRENCY_URL, params=params, timeout=None)
        if response.ok:
            return True, "OK"
        elif response.status_code == 429:
            return False, "Saturation"
        else:
            return False, f"Error_{response.status_code}"
    except requests.exceptions.RequestException:
        return False, "Crash"

def get_current_server_limit():
    """Retrieves the current max threads setting from the server."""
    try:
        response = requests.get(GET_MAX_THREADS_URL, timeout=None)
        if response.ok:
            return int(response.text)
    except Exception as e:
        log_message(f"Could not fetch current limit ({e}). Using default: {DEFAULT_START_THREADS}")
    return DEFAULT_START_THREADS

def get_recovered_limit():
    try:
        time.sleep(5) # Wait for reboot
        response = requests.get(GET_MAX_THREADS_URL, timeout=None)
        if response.ok:
            return response.text
    except Exception as e:
        return f"Failed to retrieve ({e})"
    return "Unknown"

def send_request(req_id, image_path, success_list):
    try:
        with open(image_path, 'rb') as f:
            files = {'imageFile': (os.path.basename(image_path), f, 'image/jpeg')}
            headers = {'x-client-request-id': f'diag-{req_id}'}
            response = requests.post(RECOGNIZE_URL, files=files, headers=headers, timeout=None)

        if response.ok:
            success_list.append(True)
        else:
            log_message(f"Request {req_id} failed: {response.status_code}")
    except Exception as e:
        log_message(f"Request {req_id} failed: {str(e)}")

def run_diagnostic():
    image_path = get_valid_image_path()
    if not image_path:
        print("ERROR: No valid image file found in the tests directory.")
        return

    print("="*75)
    print(f"EDGE SERVER CAPACITY DIAGNOSTIC: {SERVER_IP}")
    print(f"Using Image: {os.path.basename(image_path)} ({os.path.getsize(image_path)} bytes)")
    
    # Dynamically fetch starting threads
    start_threads = get_current_server_limit()
    print(f"Starting threads from server setting: {start_threads}")
    print("="*75)

    last_stable_limit = start_threads
    termination_reason = "Reached Max Test Limit"

    # Start loop from the current setting + STEP to push boundaries
    for threads in range(start_threads + STEP, MAX_TEST_LIMIT + 1, STEP):
        log_message(f"Testing Concurrency Level: {threads}...")

        success, reason = set_server_concurrency(threads)
        if not success:
            termination_reason = reason
            # Revert to last known stable limit on saturation
            if reason == "Saturation":
                set_server_concurrency(last_stable_limit)
            break

        success_list = []
        threads_list = []
        # Verification load: current capacity + 2 margin
        expected_successes = threads + 2
        for i in range(expected_successes):
            t = threading.Thread(target=send_request, args=(i, image_path, success_list))
            threads_list.append(t)
            t.start()

        for t in threads_list:
            t.join()

        num_success = len(success_list)
        if num_success == expected_successes:
            last_stable_limit = threads
            log_message(f"Level {threads} verified stable.")
        else:
            termination_reason = f"Load Failure ({num_success}/{expected_successes} succeeded)"
            # Revert on load failure
            set_server_concurrency(last_stable_limit)
            break

    print("\n" + "="*75)
    print("DIAGNOSTIC SUMMARY")
    print("-" * 75)
    print(f"Termination Reason:      {termination_reason}")
    print(f"Final Stable Capacity:   {last_stable_limit} threads")

    if termination_reason == "Crash":
        print("\n[RECOVERY PHASE] Server connection was lost. Waiting for reboot...")
        recovered = get_recovered_limit()
        print(f"Stored Persistent Limit: {recovered} threads")

    print("="*75)

if __name__ == "__main__":
    run_diagnostic()
