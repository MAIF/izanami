'use strict';

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

var _reactDom = require('react-dom');

var _reactDom2 = _interopRequireDefault(_reactDom);

var _react = require('react');

var _react2 = _interopRequireDefault(_react);

var _ = require('./');

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

var features = {
  project: {
    lang: {
      french: {
        active: false
      }
    },
    feature1: {
      active: true
    }
  }
};

var fallback = {
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

var App = function (_Component) {
  _inherits(App, _Component);

  function App() {
    _classCallCheck(this, App);

    return _possibleConstructorReturn(this, (App.__proto__ || Object.getPrototypeOf(App)).apply(this, arguments));
  }

  _createClass(App, [{
    key: 'render',
    value: function render() {
      return _react2.default.createElement(
        _.IzanamiProvider,
        { fetchFrom: "/api/izanami", featuresFallback: fallback },
        _react2.default.createElement(
          'div',
          null,
          _react2.default.createElement(
            _.Feature,
            { debug: true, path: 'project.lang.french' },
            _react2.default.createElement(
              _.Enabled,
              null,
              _react2.default.createElement(
                'h1',
                null,
                'Salut le monde !'
              )
            ),
            _react2.default.createElement(
              _.Disabled,
              null,
              _react2.default.createElement(
                'h1',
                null,
                'Hello World!'
              )
            )
          ),
          _react2.default.createElement(
            'div',
            null,
            _react2.default.createElement(
              _.Feature,
              { debug: true, path: 'project.feature1' },
              _react2.default.createElement(
                'p',
                null,
                'Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.'
              )
            )
          )
        )
      );
    }
  }]);

  return App;
}(_react.Component);

var experiments = {
  project: {
    lang: {
      french: {
        variant: 'A'
      }
    }
  }
};

var experimentsFallback = {
  project: {
    lang: {
      french: {
        variant: 'B'
      }
    }
  }
};

var AppAB = function (_Component2) {
  _inherits(AppAB, _Component2);

  function AppAB() {
    _classCallCheck(this, AppAB);

    return _possibleConstructorReturn(this, (AppAB.__proto__ || Object.getPrototypeOf(AppAB)).apply(this, arguments));
  }

  _createClass(AppAB, [{
    key: 'render',
    value: function render() {
      return _react2.default.createElement(
        _.IzanamiProvider,
        { fetchFrom: "/api/izanami", experimentsFallback: experimentsFallback },
        _react2.default.createElement(
          'div',
          null,
          _react2.default.createElement(
            _.Experiment,
            { debug: true, path: 'project.lang.french', 'default': "B" },
            _react2.default.createElement(
              _.Variant,
              { id: 'A' },
              _react2.default.createElement(
                'h1',
                null,
                'Salut le monde !'
              )
            ),
            _react2.default.createElement(
              _.Variant,
              { id: 'B' },
              _react2.default.createElement(
                'h1',
                null,
                'Salut les biatchs !'
              )
            )
          )
        )
      );
    }
  }]);

  return AppAB;
}(_react.Component);

_reactDom2.default.render(_react2.default.createElement(App, null), document.getElementById('app'));
_reactDom2.default.render(_react2.default.createElement(AppAB, null), document.getElementById('app2'));