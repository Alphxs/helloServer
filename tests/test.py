import requests
import time
import threading
import uuid
import os
import csv
from datetime import datetime

# --- Configuration ---
PHONE_IP = "10.131.239.97"
SERVER_PORT = 8080
BASE_URL = f"http://{PHONE_IP}:{SERVER_PORT}"
RECOGNIZE_URL = f"{BASE_URL}/recognize"

# Settings
IMAGE_TO_SEND = "test_image_1.jpg"
NUM_THREADS = 3
REQUEST_DELAY = 0.5
CSV_FILE = "test_results.csv"

# Thread-safe lock for writing to the CSV file
csv_lock = threading.Lock()

def get_timestamp():
    return datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]

def initialize_csv():
    """Creates the CSV file with headers if it doesn't exist."""
    if not os.path.exists(CSV_FILE):
        with open(CSV_FILE, mode='w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(["Request_ID", "Timestamp_Sent", "Timestamp_Received", "Status"])

def log_to_csv(request_id, sent_ts, received_ts, status):
    """Writes a single request's data to the CSV file."""
    with csv_lock:
        with open(CSV_FILE, mode='a', newline='') as f:
            writer = csv.writer(f)
            writer.writerow([request_id, sent_ts, received_ts, status])

def stress_test_worker(thread_id):
    """Infinite loop for a single thread to send image requests."""
    print(f"[{get_timestamp()}] [Thread-{thread_id}] Started.")

    if not os.path.exists(IMAGE_TO_SEND):
        with open(IMAGE_TO_SEND, 'wb') as f: f.write(b'\0')

    while True:
        client_request_id = str(uuid.uuid4())

        # Capture Sent Timestamp
        ts_sent = get_timestamp()

        status_result = "Failed"
        ts_received = "N/A"

        try:
            with open(IMAGE_TO_SEND, 'rb') as f:
                files = {'imageFile': (IMAGE_TO_SEND, f, 'image/jpeg')}
                print(f"[{get_timestamp()}] [Thread-{thread_id}] Sending Req: {client_request_id}")

                response = requests.post(
                    RECOGNIZE_URL,
                    files=files,
                    headers={"X-Client-Request-ID": client_request_id},
                    timeout=10
                )

            receive_time = time.time()
            ts_received = get_timestamp()

            if response.status_code in [200, 202]:
                data = response.json()

                # Check if data is a list or a dict before calling .get()
                if isinstance(data, dict):
                    server_task_id = data.get("taskId", "N/A")
                else:
                    # If it's a list, it's likely the final recognition results
                    server_task_id = "COMPLETED_LIST"

                status_result = "Success"
                print(f"[{ts_received}] [Thread-{thread_id}] ✅ OK! ClientID: {client_request_id} ({response.status_code})")
            else:
                status_result = f"Error_{response.status_code}"
                print(f"[{ts_received}] [Thread-{thread_id}] ⚠️ Server Error {response.status_code}")

        except Exception as e:
            ts_received = get_timestamp()
            status_result = f"Exception: {type(e).__name__}"
            print(f"[{ts_received}] [Thread-{thread_id}] ❌ Connection Failed: {e}")

        # Save data to CSV
        log_to_csv(client_request_id, ts_sent, ts_received, status_result)

        time.sleep(REQUEST_DELAY)

if __name__ == "__main__":
    initialize_csv()
    print(f"--- Starting Stress Test ---")
    print(f"Logging to: {CSV_FILE}")
    print(f"Threads: {NUM_THREADS}\n")

    threads = []
    try:
        for i in range(NUM_THREADS):
            t = threading.Thread(target=stress_test_worker, args=(i,), daemon=True)
            threads.append(t)
            t.start()
            time.sleep(0.1)

        while True:
            time.sleep(1)

    except KeyboardInterrupt:
        print("\nStopping stress test. CSV file finalized.")