import requests
import threading
import time
import os

# --- Configuration ---
SERVER_IP = "172.20.10.9"  # !!! IMPORTANT: Replace with Android device's IP address
RECOGNIZE_URL = f"http://{SERVER_IP}:8080/recognize"
DOWNLOAD_URL = f"http://{SERVER_IP}:8080/download"

# Batch settings
BATCH_SIZE_RECOG = 10      # Number of recognition requests per batch
BATCH_SIZE_DOWNLOAD = 0    # Number of download requests per batch
INTERVAL_SECONDS = 3       # Time to wait between batches
TOTAL_BATCHES = None       # Number of batches to send (set to None for infinite)

IMAGE_FILE = "test_image_1.jpg"
DOWNLOAD_FILENAME = "example.txt"

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

def send_recognition_request(batch_id, req_id):
    """Sends a single image recognition request to the server."""
    log_message(f"[{get_timestamp()}] [B-{batch_id}] [RECOG-{req_id}] Sending request...")
    try:
        with open(IMAGE_FILE, 'rb') as f:
            files = {'imageFile': (IMAGE_FILE, f, 'image/jpeg')}
            headers = {'x-client-request-id': f'batch-{batch_id}-recog-{req_id}'}
            response = requests.post(RECOGNIZE_URL, files=files, headers=headers, timeout=120)

        if response.ok:
            log_message(f"[{get_timestamp()}] [B-{batch_id}] [RECOG-{req_id}] SUCCESS (HTTP {response.status_code})")
        else:
            log_message(f"[{get_timestamp()}] [B-{batch_id}] [RECOG-{req_id}] FAILED (HTTP {response.status_code}): {response.text}")

    except requests.exceptions.RequestException as e:
        log_message(f"[{get_timestamp()}] [B-{batch_id}] [RECOG-{req_id}] ERROR: {e}")

def send_download_request(batch_id, req_id):
    """Sends a single file download request to the server."""
    log_message(f"[{get_timestamp()}] [B-{batch_id}] [DOWNLOAD-{req_id}] Sending request for {DOWNLOAD_FILENAME}...")
    try:
        params = {'file': DOWNLOAD_FILENAME}
        response = requests.get(DOWNLOAD_URL, params=params, timeout=30)
        if response.ok:
            log_message(f"[{get_timestamp()}] [B-{batch_id}] [DOWNLOAD-{req_id}] SUCCESS (HTTP {response.status_code}) - Size: {len(response.content)} bytes")
        else:
            log_message(f"[{get_timestamp()}] [B-{batch_id}] [DOWNLOAD-{req_id}] FAILED (HTTP {response.status_code}): {response.text}")
    except requests.exceptions.RequestException as e:
        log_message(f"[{get_timestamp()}] [B-{batch_id}] [DOWNLOAD-{req_id}] ERROR: {e}")

def run_batch(batch_id):
    """Executes a single batch of requests asynchronously."""
    log_message(f"\n--- Starting Batch {batch_id} ---")
    threads = []

    # Prepare recognition threads
    for i in range(BATCH_SIZE_RECOG):
        t = threading.Thread(target=send_recognition_request, args=(batch_id, i + 1))
        threads.append(t)

    # Prepare download threads
    for i in range(BATCH_SIZE_DOWNLOAD):
        t = threading.Thread(target=send_download_request, args=(batch_id, i + 1))
        threads.append(t)

    # Start all threads in the current batch
    for t in threads:
        t.start()
        time.sleep(0.001)

    # Wait for all threads in this specific batch to finish
    for t in threads:
        t.join()

    log_message(f"[{get_timestamp()}] [SYSTEM] Batch {batch_id} finished.")

# --- Main Execution ---
if __name__ == "__main__":
    batch_info = "Infinite" if TOTAL_BATCHES is None else str(TOTAL_BATCHES)
    log_message(f"[{get_timestamp()}] [SYSTEM] Starting periodic batch test.")
    log_message(f"[{get_timestamp()}] [SYSTEM] Config: {batch_info} batches, {BATCH_SIZE_RECOG} recog + {BATCH_SIZE_DOWNLOAD} download per batch every {INTERVAL_SECONDS}s.")

    create_dummy_image()

    b = 1
    try:
        while TOTAL_BATCHES is None or b <= TOTAL_BATCHES:
            # Start the batch in its own manager thread so it doesn't block the next interval
            batch_thread = threading.Thread(target=run_batch, args=(b,))
            batch_thread.start()

            if TOTAL_BATCHES is None or b < TOTAL_BATCHES:
                time.sleep(INTERVAL_SECONDS)
            
            b += 1
            
    except KeyboardInterrupt:
        log_message(f"\n[{get_timestamp()}] [SYSTEM] Test stopped by user.")

    log_message(f"\n[{get_timestamp()}] [SYSTEM] Main loop terminated. Waiting for active batches to finish...")
