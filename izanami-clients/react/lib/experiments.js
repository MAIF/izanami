'use strict';

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.ExperimentsProvider = exports.Experiment = exports.Won = exports.Variant = undefined;

var _createClass = function () { function defineProperties(target, props) { for (var i = 0; i < props.length; i++) { var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; descriptor.configurable = true; if ("value" in descriptor) descriptor.writable = true; Object.defineProperty(target, descriptor.key, descriptor); } } return function (Constructor, protoProps, staticProps) { if (protoProps) defineProperties(Constructor.prototype, protoProps); if (staticProps) defineProperties(Constructor, staticProps); return Constructor; }; }();

require('es6-shim');

require('whatwg-fetch');

var _esSymbol = require('es-symbol');

var _esSymbol2 = _interopRequireDefault(_esSymbol);

var _react = require('react');

var _react2 = _interopRequireDefault(_react);

var _propTypes = require('prop-types');

var _propTypes2 = _interopRequireDefault(_propTypes);

var _deepEqual = require('deep-equal');

var _deepEqual2 = _interopRequireDefault(_deepEqual);

var _deepmerge = require('deepmerge');

var _deepmerge2 = _interopRequireDefault(_deepmerge);

var _lodash = require('lodash');

var _lodash2 = _interopRequireDefault(_lodash);

var _api = require('./api');

var Api = _interopRequireWildcard(_api);

function _interopRequireWildcard(obj) { if (obj && obj.__esModule) { return obj; } else { var newObj = {}; if (obj != null) { for (var key in obj) { if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key]; } } newObj.default = obj; return newObj; } }

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _classCallCheck(instance, Constructor) { if (!(instance instanceof Constructor)) { throw new TypeError("Cannot call a class as a function"); } }

function _possibleConstructorReturn(self, call) { if (!self) { throw new ReferenceError("this hasn't been initialised - super() hasn't been called"); } return call && (typeof call === "object" || typeof call === "function") ? call : self; }

function _inherits(subClass, superClass) { if (typeof superClass !== "function" && superClass !== null) { throw new TypeError("Super expression must either be null or a function, not " + typeof superClass); } subClass.prototype = Object.create(superClass && superClass.prototype, { constructor: { value: subClass, enumerable: false, writable: true, configurable: true } }); if (superClass) Object.setPrototypeOf ? Object.setPrototypeOf(subClass, superClass) : subClass.__proto__ = superClass; }

if (!window.Symbol) {
  window.Symbol = _esSymbol2.default;
}

var Variant = exports.Variant = function (_Component) {
  _inherits(Variant, _Component);

  function Variant() {
    _classCallCheck(this, Variant);

    return _possibleConstructorReturn(this, (Variant.__proto__ || Object.getPrototypeOf(Variant)).apply(this, arguments));
  }

  _createClass(Variant, [{
    key: 'render',
    value: function render() {
      return this.props.children;
    }
  }]);

  return Variant;
}(_react.Component);

Variant.propTypes = {
  id: _propTypes2.default.string.isRequired
};

var Won = exports.Won = function (_Component2) {
  _inherits(Won, _Component2);

  function Won() {
    _classCallCheck(this, Won);

    return _possibleConstructorReturn(this, (Won.__proto__ || Object.getPrototypeOf(Won)).apply(this, arguments));
  }

  _createClass(Won, [{
    key: 'componentDidMount',
    value: function componentDidMount() {
      if (this.props.notifyWon && !this.mounted) {
        this.mounted = true;
        var notifyWon = this.props.notifyWon;
        var url = notifyWon.indexOf('experiment=') > -1 ? notifyWon : notifyWon + '?experiment=' + this.props.path;
        fetch(url, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ experiment: this.props.path })
        }).then(function (r) {
          return r.json();
        });
      }
    }
  }, {
    key: 'componentWillUnmount',
    value: function componentWillUnmount() {
      this.mounted = false;
    }
  }, {
    key: 'render',
    value: function render() {
      return this.props.children;
    }
  }]);

  return Won;
}(_react.Component);

Won.propTypes = {
  path: _propTypes2.default.string.isRequired,
  notifyWon: _propTypes2.default.string.isRequired,
  notifyWonHeaders: _propTypes2.default.object.isRequired
};

var Experiment = exports.Experiment = function (_Component3) {
  _inherits(Experiment, _Component3);

  function Experiment() {
    var _ref;

    var _temp, _this3, _ret;

    _classCallCheck(this, Experiment);

    for (var _len = arguments.length, args = Array(_len), _key = 0; _key < _len; _key++) {
      args[_key] = arguments[_key];
    }

    return _ret = (_temp = (_this3 = _possibleConstructorReturn(this, (_ref = Experiment.__proto__ || Object.getPrototypeOf(Experiment)).call.apply(_ref, [this].concat(args))), _this3), _this3.state = {
      experiments: {}
    }, _this3.onExperimentsChanged = function (_ref2) {
      var experiments = _ref2.experiments;

      if (!(0, _deepEqual2.default)(_this3.state.experiments, experiments)) {
        _this3.setState({ experiments: experiments });
      }
    }, _temp), _possibleConstructorReturn(_this3, _ret);
  }

  _createClass(Experiment, [{
    key: 'componentDidMount',
    value: function componentDidMount() {
      var fetchFrom = this.context.__fetchFrom;
      if (fetchFrom) {
        Api.register(fetchFrom, this.onExperimentsChanged);
      }
      if (this.props.notifyDisplay && !this.mounted) {
        this.mounted = true;
        var notifyDisplay = this.props.notifyDisplay;
        var url = notifyDisplay.indexOf('experiment=') > -1 ? notifyDisplay : notifyDisplay + '?experiment=' + this.props.path;
        fetch(url, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ experiment: this.props.path })
        }).then(function (r) {
          return r.json();
        });
      }
    }
  }, {
    key: 'componentWillUnmount',
    value: function componentWillUnmount() {
      this.mounted = false;
      var fetchFrom = this.context.__fetchFrom;
      if (fetchFrom) {
        Api.unregister(fetchFrom, this.onExperimentsChanged);
      }
    }
  }, {
    key: 'render',
    value: function render() {
      var children = this.props.children;
      var path = this.props.path.replace(/:/g, '.');
      var experiments = (0, _deepmerge2.default)(this.context.__mergedExperiments, this.state.experiments);
      var value = (_lodash2.default.get(experiments, path) || { variant: null }).variant || this.props.default;

      console.log('Value', value);

      var childrenArray = Array.isArray(children) ? children : [children];
      var variantChildren = childrenArray.filter(function (c) {
        return c.type === Variant;
      }).filter(function (c) {
        return c.props.id === value;
      });
      var debug = !!this.context.__debug || this.props.debug;
      if (variantChildren.length === 0) {
        if (debug) console.log('Experiment \'' + path + '\' has no valid Variant ' + value + '. Please provide one.');
        return null;
      } else {
        var variant = variantChildren[0] || null;
        if (variant && debug) {
          var color = '#' + (~~(Math.random() * (1 << 24))).toString(16);
          return _react2.default.createElement(
            'div',
            { className: 'izanami-experiment', title: 'Experiment ' + path + ':\xA0variant is ' + value, style: { position: 'relative', outline: '1px solid ' + color } },
            _react2.default.createElement(
              'span',
              { style: { padding: 2, color: 'white', backgroundColor: color, position: 'absolute', top: -17, left: -1, zIndex: 100000 } },
              'Experiment ',
              _react2.default.createElement(
                'span',
                { style: { fontWeight: 'bold' } },
                path
              ),
              ':\xA0variant is ',
              _react2.default.createElement(
                'span',
                { style: { fontWeight: 'bold' } },
                value
              )
            ),
            variant
          );
        }
        return variant;
      }
    }
  }]);

  return Experiment;
}(_react.Component);

Experiment.contextTypes = {
  __mergedExperiments: _propTypes2.default.object,
  __debug: _propTypes2.default.bool
};
Experiment.propTypes = {
  path: _propTypes2.default.string.isRequired,
  default: _propTypes2.default.string,
  debug: _propTypes2.default.bool,
  notifyDisplay: _propTypes2.default.string,
  notifyDisplayHeaders: _propTypes2.default.object
};
Experiment.defaultProps = {
  debug: false,
  notifyDisplayHeaders: {}
};

var ExperimentsProvider = exports.ExperimentsProvider = function (_Component4) {
  _inherits(ExperimentsProvider, _Component4);

  function ExperimentsProvider() {
    var _ref3;

    var _temp2, _this4, _ret2;

    _classCallCheck(this, ExperimentsProvider);

    for (var _len2 = arguments.length, args = Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
      args[_key2] = arguments[_key2];
    }

    return _ret2 = (_temp2 = (_this4 = _possibleConstructorReturn(this, (_ref3 = ExperimentsProvider.__proto__ || Object.getPrototypeOf(ExperimentsProvider)).call.apply(_ref3, [this].concat(args))), _this4), _this4.state = {
      experiments: _this4.props.experiments,
      fallback: _this4.props.fallback,
      debug: _this4.props.debug
    }, _temp2), _possibleConstructorReturn(_this4, _ret2);
  }

  _createClass(ExperimentsProvider, [{
    key: 'getChildContext',
    value: function getChildContext() {
      return {
        __debug: this.state.debug,
        __experiments: this.state.experiments,
        __fallback: this.state.fallback,
        __mergedExperiments: (0, _deepmerge2.default)(this.state.fallback, this.state.experiments),
        __fetchFrom: this.props.fetchFrom
      };
    }
  }, {
    key: 'componentWillReceiveProps',
    value: function componentWillReceiveProps(nextProps) {
      if (!(0, _deepEqual2.default)(nextProps.experiments, this.props.experiments)) {
        this.setState({ experiments: nextProps.experiments });
      }
      if (!(0, _deepEqual2.default)(nextProps.fallback, this.props.fallback)) {
        this.setState({ fallback: nextProps.fallback });
      }
      if (nextProps.debug !== this.props.debug) {
        this.setState({ debug: nextProps.debug });
      }
    }
  }, {
    key: 'render',
    value: function render() {
      return this.props.children || null;
    }
  }]);

  return ExperimentsProvider;
}(_react.Component);

ExperimentsProvider.childContextTypes = {
  __experiments: _propTypes2.default.object,
  __fallback: _propTypes2.default.object,
  __mergedExperiments: _propTypes2.default.object,
  __debug: _propTypes2.default.bool,
  __fetchFrom: _propTypes2.default.string
};
ExperimentsProvider.propTypes = {
  experiments: _propTypes2.default.object.isRequired,
  fallback: _propTypes2.default.object,
  fetchFrom: _propTypes2.default.string,
  debug: _propTypes2.default.bool
};
ExperimentsProvider.defaultProps = {
  fallback: {}
};