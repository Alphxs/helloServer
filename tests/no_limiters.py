import requests
import threading
import time
import os

# --- Configuration ---
SERVER_IP = "192.168.50.238"  # !!! IMPORTANT: Replace with your Android device's IP address
SERVER_URL = f"http://{SERVER_IP}:8080/recognize"
NUM_REQUESTS = 10  # Number of concurrent requests to send
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
    log_message(f"[{get_timestamp()}] [CLIENT-{request_num}] Sending request...")
    try:
        with open(IMAGE_FILE, 'rb') as f:
            files = {'imageFile': (IMAGE_FILE, f, 'image/jpeg')}
            headers = {'x-client-request-id': f'client-{request_num}'}
            response = requests.post(SERVER_URL, files=files, headers=headers, timeout=120) # 120-second timeout

        if response.ok:
            log_message(f"[{get_timestamp()}] [CLIENT-{request_num}] SUCCESS (HTTP {response.status_code}): {response.json()}")
        else:
            log_message(f"[{get_timestamp()}] [CLIENT-{request_num}] FAILED (HTTP {response.status_code}): {response.text}")

    except requests.exceptions.RequestException as e:
        log_message(f"[{get_timestamp()}] [CLIENT-{request_num}] CRITICAL ERROR: Could not connect to server. {e}")

# --- Main Execution ---
if __name__ == "__main__":
    log_message(f"[{get_timestamp()}] [SYSTEM] Starting test: Simulating {NUM_REQUESTS} concurrent requests.")

    # Create the dummy image file for testing
    create_dummy_image()

    threads = []
    start_time = time.time()

    # Create and start threads
    for i in range(NUM_REQUESTS):
        thread = threading.Thread(target=send_recognition_request, args=(i + 1,))
        threads.append(thread)
        thread.start()
        time.sleep(0.1) # Stagger the requests slightly to simulate more realistic arrivals

    # Wait for all threads to complete
    for thread in threads:
        thread.join()

    end_time = time.time()
    duration = end_time - start_time

    log_message(f"\n[{get_timestamp()}] [SYSTEM] Test finished.")
    log_message(f"[{get_timestamp()}] [SYSTEM] Total duration for {NUM_REQUESTS} requests: {duration:.2f} seconds.")
