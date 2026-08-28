use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;

use crate::codec::{decode_asset, read_file_fully, DecodeError, KEY_SIZE};

enum Job {
    Read {
        path: String,
        done: Box<dyn FnOnce(Result<Vec<u8>, DecodeError>) + Send>,
    },
    Decode {
        key: [u8; KEY_SIZE],
        stored: Vec<u8>,
        done: Box<dyn FnOnce(Result<Vec<u8>, DecodeError>) + Send>,
    },
}

fn pool_tx() -> &'static Sender<Job> {
    static POOL: OnceLock<Sender<Job>> = OnceLock::new();
    POOL.get_or_init(|| {
        let (tx, rx) = mpsc::channel::<Job>();
        let shared: Arc<Mutex<Receiver<Job>>> = Arc::new(Mutex::new(rx));
        let workers = std::cmp::max(
            2,
            std::thread::available_parallelism()
                .map(|n| n.get())
                .unwrap_or(2),
        );
        for _ in 0..workers {
            let shared = shared.clone();
            thread::spawn(move || loop {
                let job = {
                    let Ok(guard) = shared.lock() else {
                        break;
                    };
                    match guard.recv() {
                        Ok(job) => job,
                        Err(_) => break,
                    }
                };
                match job {
                    Job::Read { path, done } => done(read_file_fully(&path)),
                    Job::Decode { key, stored, done } => done(decode_asset(&key, &stored)),
                }
            });
        }
        tx
    })
}

pub fn read_file_async(path: String, done: impl FnOnce(Result<Vec<u8>, DecodeError>) + Send + 'static) {
    let _ = pool_tx().send(Job::Read {
        path,
        done: Box::new(done),
    });
}

pub fn decode_asset_async(
    key: [u8; KEY_SIZE],
    stored: Vec<u8>,
    done: impl FnOnce(Result<Vec<u8>, DecodeError>) + Send + 'static,
) {
    let _ = pool_tx().send(Job::Decode {
        key,
        stored,
        done: Box::new(done),
    });
}
