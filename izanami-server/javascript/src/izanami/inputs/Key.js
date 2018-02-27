import React from 'react';

const Key = props => {
  const values = props.value.split(":").filter(e => !!e);
  const size = values.length;
  return (
    <div className="key-value">
        <span className="key-value-wrapper">
        {values.map( (part,i) =>
          [
            <div className="key-value-value" key={`key-value-${props.value}-${i}`} >
              <span className="key-value-value-label">{part}</span>
            </div>,
            <div className="key-value-value-sep" key={`key-value-sep-${props.value}-${i}`}>
              {i < (size - 1) && <span className="key-value-value-sep-value">:</span>}
            </div>
          ])}
        </span>
    </div>
  )
};

export {Key};