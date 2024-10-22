import React from "react";
import { SearchModalContent } from "./SearchModalContent";
import { Modal } from "../Modal";

export function SearchModal(props: {
  currentTenant?: string;
  availableTenants?: string[];
  isOpenModal: boolean;
  onClose: () => void;
}) {
  const { currentTenant, availableTenants, isOpenModal, onClose } = props;
  if (!isOpenModal) return null;

  return (
    <Modal visible={isOpenModal} onClose={() => onClose()} position="top">
      <SearchModalContent
        tenant={currentTenant}
        allTenants={availableTenants}
        onClose={() => onClose()}
      />
    </Modal>
  );
}
