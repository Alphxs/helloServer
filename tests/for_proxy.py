import requests
import threading
import time
import os

# --- Configuration ---
SERVER_IP = "192.168.50.4"  # !!! IMPORTANT: Replace with your Android device's IP address
RECOGNIZE_URL = f"http://{SERVER_IP}:8080/recognize"
STATUS_URL = f"http://{SERVER_IP}:8080/status"
NUM_RECOGNIZE = 50  # Number of concurrent recognition requests
NUM_STATUS = 50     # Number of concurrent status (battery) requests
IMAGE_FILE = "test_image_1.jpg"

# --- Helper Functions ---
def get_timestamp():
    return time.strftime("%H:%M:%S", time.localtime())

def log_message(message):
    print(message)

def create_dummy_image():
    """Creates a small dummy image file if it doesn't exist."""
    if not os.path.exists(IMAGE_FILE):
        log_message(f"[{get_timestamp()}] [SYSTEM] Creating dummy file for {IMAGE_FILE}")
        with open(IMAGE_FILE, 'wb') as f:
            f.write(b'\x00\x01\x02\x03') # Some dummy bytes

def send_recognition_request(request_num):
    """Sends a single image recognition request to the server."""
    log_message(f"[{get_timestamp()}] [RECOG-{request_num}] Sending request...")
    try:
        with open(IMAGE_FILE, 'rb') as f:
            files = {'imageFile': (IMAGE_FILE, f, 'image/jpeg')}
            headers = {'x-client-request-id': f'recog-{request_num}'}
            response = requests.post(RECOGNIZE_URL, files=files, headers=headers, timeout=120)

        if response.ok:
            log_message(f"[{get_timestamp()}] [RECOG-{request_num}] SUCCESS (HTTP {response.status_code}): {response.json()}")
        else:
            log_message(f"[{get_timestamp()}] [RECOG-{request_num}] FAILED (HTTP {response.status_code}): {response.text}")

    except requests.exceptions.RequestException as e:
        log_message(f"[{get_timestamp()}] [RECOG-{request_num}] ERROR: {e}")

def send_status_request(request_num):
    """Sends a single battery status request to the server."""
    log_message(f"[{get_timestamp()}] [STATUS-{request_num}] Sending request...")
    try:
        response = requests.get(STATUS_URL, timeout=10)
        if response.ok:
            log_message(f"[{get_timestamp()}] [STATUS-{request_num}] SUCCESS (HTTP {response.status_code}): {response.json()}")
        else:
            log_message(f"[{get_timestamp()}] [STATUS-{request_num}] FAILED (HTTP {response.status_code}): {response.text}")
    except requests.exceptions.RequestException as e:
        log_message(f"[{get_timestamp()}] [STATUS-{request_num}] ERROR: {e}")

# --- Main Execution ---
if __name__ == "__main__":
    log_message(f"[{get_timestamp()}] [SYSTEM] Starting test: {NUM_RECOGNIZE} Recognition + {NUM_STATUS} Status requests.")

    # Create the dummy image file for testing
    create_dummy_image()

    threads = []

    # Create threads for recognition
    for i in range(NUM_RECOGNIZE):
        t = threading.Thread(target=send_recognition_request, args=(i + 1,))
        threads.append(t)

    # Create threads for status
    for i in range(NUM_STATUS):
        t = threading.Thread(target=send_status_request, args=(i + 1,))
        threads.append(t)

    start_time = time.time()

    # Start all threads
    for t in threads:
        t.start()
        # very tiny sleep to avoid overwhelming the local OS thread scheduler
        # but still keeping them "at the same time"
        time.sleep(0.01)

    # Wait for all threads to complete
    for t in threads:
        t.join()

    end_time = time.time()
    duration = end_time - start_time

    log_message(f"\n[{get_timestamp()}] [SYSTEM] Test finished.")
    log_message(f"[{get_timestamp()}] [SYSTEM] Total duration for {NUM_RECOGNIZE+NUM_STATUS} requests: {duration:.2f} seconds.")
