
let callback = [];

if (!!window.EventSource) {
  console.log('Initialising server sent event');
  const evtSource = new EventSource("/api/events");
  evtSource.addEventListener('open', e => {
    console.log('SSE opened');
  }, false);
  evtSource.addEventListener('error', e => {
    console.error('SSE error', e);
  }, false);
  evtSource.addEventListener('message', e => {
    console.log('New event', e);
    const data = JSON.parse(e.data);
    callback.forEach(cb => cb(data));
  }, false);
}


export function addCallback(cb) {
  callback = [...callback, cb];
}

export function removeCallback(cb) {
  callback = callback.filter(c => c === cb);
}