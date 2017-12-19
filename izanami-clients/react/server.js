const express = require('express');

const app = express();


app.get('/izanami', (req, resp) => {
  resp.status(200).send({
    experiments: {
      project: {
        lang: {
          french: {
            variant: 'A'
          }
        }
      }
    },
    features: {
      project: {
        lang: {
          french: {
            active: true
          }
        },
        feature1: {
          active: true
        }
      }
    }
  });
});

const port = 3200;
app.listen(port, () => {
  console.log(`opun-demo is listening on ${port} !`)
});