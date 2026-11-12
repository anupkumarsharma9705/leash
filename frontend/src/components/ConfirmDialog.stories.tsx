import type { Meta, StoryObj } from '@storybook/react';
import { ConfirmDialog } from './ConfirmDialog';

const meta = {
  title: 'Components/ConfirmDialog',
  component: ConfirmDialog,
  parameters: {
    layout: 'centered',
  },
  tags: ['autodocs'],
  argTypes: {
    tone: {
      control: 'select',
      options: ['primary', 'danger'],
    },
    pending: {
      control: 'boolean',
    },
  },
} satisfies Meta<typeof ConfirmDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Primary: Story = {
  args: {
    title: 'Confirm action',
    description: 'Are you sure you want to proceed? This action cannot be undone.',
    confirmLabel: 'Confirm',
    onConfirm: () => console.log('Confirmed'),
    onCancel: () => console.log('Cancelled'),
  },
};

export const Danger: Story = {
  args: {
    title: 'Delete item?',
    description: 'This will permanently delete the item. There is no way to recover it.',
    confirmLabel: 'Delete',
    pendingLabel: 'Deleting…',
    tone: 'danger',
    onConfirm: () => console.log('Deleted'),
    onCancel: () => console.log('Cancelled'),
  },
};

export const WithError: Story = {
  args: {
    title: 'Operation failed',
    description: 'Something went wrong. Please try again.',
    confirmLabel: 'Retry',
    tone: 'danger',
    error: 'Network error. Please check your connection.',
    onConfirm: () => console.log('Retry'),
    onCancel: () => console.log('Cancelled'),
  },
};

export const WithChildren: Story = {
  args: {
    title: 'Suspend agent?',
    description: 'Suspending is immediate for new payment requests.',
    confirmLabel: 'Suspend agent',
    pendingLabel: 'Suspending…',
    tone: 'danger',
    onConfirm: () => console.log('Suspended'),
    onCancel: () => console.log('Cancelled'),
    children: (
      <dl className="facts">
        <div className="facts__row">
          <dt>Agent</dt>
          <dd>Invoice Agent</dd>
        </div>
        <div className="facts__row">
          <dt>Status</dt>
          <dd>ACTIVE</dd>
        </div>
      </dl>
    ),
  },
};