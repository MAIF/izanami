let callback = [];

let eventSource;

let timeout = 1000;

const IzanamiEvents = {
  start() {
    if (!!window.EventSource) {
      console.log("Initialising server sent event");
      eventSource = new EventSource(`${window.__contextPath}/api/events`);
      eventSource.addEventListener(
        "open",
        e => {
          console.log("SSE opened");
          timeout = 1000;
        },
        false
      );
      eventSource.addEventListener(
        "error",
        e => {
          console.error("SSE error", e);
          IzanamiEvents.stop();
          setTimeout(() => {
            IzanamiEvents.start();
            timeout = timeout * 2;
          }, timeout);
        },
        false
      );
      eventSource.addEventListener(
        "message",
        e => {
          console.debug("New event", e);
          const data = JSON.parse(e.data);
          if (data.type !== 'KEEP_ALIVE') {          
            callback.forEach(cb => cb(data));
          }
        },
        false
      );
    }
  },
  stop() {
    if (eventSource) {
      eventSource.close();
    }
  }
};

export { IzanamiEvents };

export function addCallback(cb) {
  callback = [...callback, cb];
}

export function removeCallback(cb) {
  callback = callback.filter(c => c === cb);
}
