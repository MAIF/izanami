import ReactDOM from 'react-dom';
import React, { Component } from 'react';
//import {applyMiddleware, createStore, combineReducers} from 'redux';
//import thunk from 'redux-thunk';
//import {Provider, connect} from 'react-redux';
import { Enabled, Disabled, Feature, IzanamiProvider, Variant, Experiment} from './';

//
// const setConfiguration = (configuration) => {
//   return (dispatch, getState) => {
//     if (!getState().configuration.initialized) {
//       dispatch({
//         type: 'SET_CONFIGURATION',
//         configuration
//       })
//     }
//   };
// };
//
// const defaultState = {
//   initialized: false,
//   app: {
//     mode: undefined
//   }
// };
//
// const configuration = (state = defaultState, action) => {
//   switch (action.type) {
//     case 'SET_CONFIGURATION':
//       return { ...state, ...action.configuration };
//     default:
//       return state;
//   }
// };
//
// const store = createStore(combineReducers({configuration}), {}, applyMiddleware(thunk));

const features = {
  project: {
    lang: {
      french: {
        active: false
      }
    },
    feature1: {
      active: true
    },
    feature2: {
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
    },
    feature2: {
      active: true
    },
    feature3: {
      active: false
    },
  }
};

class App extends Component {

  // componentDidMount() {
  //   store.dispatch(setConfiguration({
  //     initialized: true,
  //     app: {
  //       mode: "test"
  //     }
  //   }));
  // }

  render() {
    //<Provider store={store}>
    return (
      <IzanamiProvider fetchFrom={"/api/izanami"} featuresFallback={fallback}>
        <div>
          <FeatureApp />
          <div>
            <Feature debug path="project.feature1">
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
      </IzanamiProvider>
    )
  }
  // </Provider>
}

class FeatureApp extends Component {
  render() {
    return <div>
      <Feature debug path="project.lang.french">
        <Enabled>
          <h1>Salut le monde !</h1>
        </Enabled>
        <Disabled>
          <h1>Hello World!</h1>
        </Disabled>
      </Feature>
      <Feature debug path={["project.feature2","project.feature3"]}>
        <Enabled>
          <h1>Actif</h1>
        </Enabled>
        <Disabled>
          <h1>Inactif</h1>
        </Disabled>
      </Feature>
    </div>
  }
}

// const FeatureApp = connect(store => {
//   console.log(store);
//   return store.configuration.app;
// })(FeatureAppComponent);

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
      <IzanamiProvider fetchFrom={"/api/izanami"}  experimentsFallback={experimentsFallback}>
        <div>
          <Experiment debug path="project.lang.french" default={"B"}>
            <Variant id="A">
              <h1>Salut le monde !</h1>
            </Variant>
            <Variant id="B">
              <h1>Salut les biatchs !</h1>
            </Variant>
          </Experiment>
        </div>
      </IzanamiProvider>
    );
  }
}


ReactDOM.render(<App />, document.getElementById('app'));
ReactDOM.render(<AppAB />, document.getElementById('app2'));
