# React client

`react-izanami` is a simple set of React components to leverage the power of Opun Izanami feature flipping and experiments.


## Feature flipping

```jsx
import ReactDOM from 'react-dom';
import React, { Component } from 'react';

import { Enabled, Disabled, Feature, FeatureProvider } from './features';

const features = {
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
};

const fallback = {
  project: {
    lang: {
      french: {
        active: false
      }
    },
    feature1: {
      active: false
    }
  }
};

class App extends Component {
  render() {
    return (
      <FeatureProvider features={features} fallback={fallback}>
        <div>
          <Feature path="project.lang.french">
            <Enabled>
              <h1>Salut le monde !</h1>
            </Enabled>
            <Disabled>
              <h1>Hello World!</h1>
            </Disabled>
          </Feature>
          <div>
            <Feature path="project.feature1">
              <p>
                Lorem ipsum dolor sit amet, consectetur adipisicing elit,
                sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
                Ut enim ad minim veniam, quis nostrud exercitation ullamco
                laboris nisi ut aliquip ex ea commodo consequat.
                Duis aute irure dolor in reprehenderit in voluptate velit esse
                cillum dolore eu fugiat nulla pariatur. Excepteur sint
                occaecat cupidatat non proident, sunt in culpa qui officia
                deserunt mollit anim id est laborum.
              </p>
            </Feature>
          </div>
        </div>
      </FeatureProvider>
    );
  }
}

ReactDOM.render(<App />, document.getElementById('app'));
```

## Experiments (A/B testing)

```jsx
import ReactDOM from 'react-dom';
import React, { Component } from 'react';

import { Variant, Experiment, ExperimentsProvider } from './experiments';

const experiments = {
  project: {
    lang: {
      french: {
        variant: 'A'
      }
    }
  }
};

const experimentsFallback = {
  project: {
    lang: {
      french: {
        variant: 'B'
      }
    }
  }
};

class AppAB extends Component {
  render() {
    return (
      <ExperimentsProvider experiments={experiments} fallback={experimentsFallback}>
        <div>
          <Experiment path="project.lang.french">
            <Variant id="A">
              <h1>Salut le monde !</h1>
            </Variant>
            <Variant id="B">
              <h1>Salut les biatchs !</h1>
            </Variant>
          </Experiment>
        </div>
      </ExperimentsProvider>
    );
  }
}

ReactDOM.render(<AppAB />, document.getElementById('app'));
```
