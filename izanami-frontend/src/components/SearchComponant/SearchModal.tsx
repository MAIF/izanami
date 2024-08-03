import React from "react";
import { SearchModalContent } from "./SearchModalContent";
import { Modal } from "../Modal";

export function SearchModal(props: {
  tenant?: string;
  isOpenModal: boolean;
  onClose: () => void;
}) {
  const { tenant, isOpenModal, onClose } = props;
  if (!isOpenModal) return null;

  return (
    <Modal visible={isOpenModal} onClose={() => onClose()} position="top">
      <SearchModalContent tenant={tenant} onClose={() => onClose()} />
    </Modal>
  );
}
