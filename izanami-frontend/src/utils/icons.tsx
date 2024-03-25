import * as React from "react";

export function GlobalContextIcon(props: { className?: string }) {
  return (
    <svg
      className={props.className ? props.className : ""}
      height="16px"
      viewBox="0 0 512 512"
    >
      <path
        className="icon_context"
        fill="#FFF"
        d="M201,253.94c0,21.49-.26,42.21-.7,61.95H160.7c-.47-19.74-.7-40.46-.7-61.95s.26-42.2.7-61.94h39.6C200.77,211.74,201,232.46,201,253.94ZM381,192H503.91c5.29,5.43,8.09,11.09,8.09,16.94s-2.8,11.52-8.09,16.95H381A46.46,46.46,0,0,0,381,192Zm112.4-32H376.7c-10-63.9-29.8-117.4-55.3-151.6A256.5,256.5,0,0,1,493.3,160Zm-149.1,0H167.7c6.1-36.4,15.5-68.6,27-94.7,10.5-23.6,22.2-40.7,33.5-51.5C239.4,3.2,248.7,0,256,0s16.6,3.2,27.8,13.8c11.3,10.8,23,27.9,33.5,51.5C328.9,91.3,338.2,123.5,344.3,160Zm-209,0H18.6A256.66,256.66,0,0,1,190.6,8.4C165.1,42.6,145.3,96.1,135.3,160ZM8.1,192H131.2a641.6,641.6,0,0,0,0,128H8.1a256.89,256.89,0,0,1,0-128ZM135.3,352c10,63.9,29.8,117.4,55.3,151.6A256.66,256.66,0,0,1,18.6,352ZM270,307.39a20.54,20.54,0,0,1,18.62-11.81H511.47a20.65,20.65,0,0,1,15.94,33.73L433.07,444.59v65.55a16.52,16.52,0,0,1-26.41,13.21l-33-24.76a16.38,16.38,0,0,1-6.61-13.2v-40.8L272.67,329.26A20.56,20.56,0,0,1,270,307.39Zm-70.14-115H344.3a39.28,39.28,0,0,0,0,33.5H199.9c-6.22-5.37-9.5-11-9.5-16.75S193.68,197.76,199.9,192.39ZM171.13,361.28l76.6-1.41c-5.51,32.85,54.76,62.52,44.38,86.08-9.48,21.3-6,37.53-16.19,47.28-10.11,9.57-18.51,12.45-25.09,12.45s-15-2.88-25.1-12.45c-10.19-9.75-20.75-25.18-30.23-46.48C185,423.28,176.64,394.22,171.13,361.28Z"
      />
    </svg>
  );
}
